import base64
import hashlib
import html
import io
import json
import secrets
import copy
from pathlib import Path
from uuid import UUID
from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import Response, HTMLResponse
from sqlalchemy import select
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey
from cryptography.hazmat.primitives import serialization
from cryptography.exceptions import InvalidSignature
from .db import get_db, utcnow
from .models import Report, Revocation, uid
from .auth import current_user, require_admin
from .inspections import owned, latest, Strict
from .registry import score
from .scoring import incomplete_for_report, overall_score
from .media import evidence_summary
from .billing import consume_report_entitlement, lock_account
from .audit import audit
from .expert_analysis import analyze
from .config import settings
from pydantic import Field

router=APIRouter(tags=['Reports'])
public_router=APIRouter()


def signing_key():
    path=Path(settings.signing_key_path)
    if not path.exists():
        if settings.app_env=='production':raise RuntimeError('Missing signing key')
        path.parent.mkdir(parents=True,exist_ok=True)
        key=Ed25519PrivateKey.generate()
        # Exclusive create prevents multiple workers overwriting a signing identity.
        try:
            with path.open('xb') as file:
                file.write(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
            path.chmod(0o600)
        except FileExistsError:pass
    return serialization.load_pem_private_key(path.read_bytes(),password=None)


def canonical(payload):
    return json.dumps(payload,sort_keys=True,separators=(',',':'),ensure_ascii=False,allow_nan=False)


def verify_signature(report):
    try:
        encoded=canonical(report.payload)
        digest=hashlib.sha256(encoded.encode()).hexdigest()
        if encoded!=report.canonical_json or digest!=report.hash:return False
        key=Ed25519PublicKey.from_public_bytes(base64.b64decode(report.public_key))
        key.verify(base64.b64decode(report.signature),bytes.fromhex(digest))
        return True
    except (ValueError,InvalidSignature,TypeError):return False


def report_info(report,revoked=False):
    return {'id':report.id,'inspection_id':report.inspection_id,'public_token':report.public_token,'verification_url':settings.public_report_base_url.rstrip('/')+'/r/'+report.public_token,
            'status':'REVOKED' if revoked else 'VALID' if verify_signature(report) else 'INVALID_SIGNATURE',
            'hash':report.hash,'signature':report.signature,'public_key':report.public_key,'key_version':report.key_version,'payload':report.payload}


@router.post('/inspections/{inspection_id}/finalize')
@router.post('/inspections/{inspection_id}/reports')
def finalize(inspection_id:UUID,user=Depends(current_user),db=Depends(get_db, scope='function')):
    lock_account(db,user)
    i=owned(db,user,inspection_id)
    old=db.scalar(select(Report).where(Report.inspection_id==i.id))
    if old:return report_info(old,bool(db.get(Revocation,old.id)))
    if i.status=='CANCELLED' or not i.definitions:raise HTTPException(409,'Inspection is cancelled or lacks a capability snapshot')
    results=latest(db,i)
    missing=incomplete_for_report(i.definitions,results,i.score_rule_version)
    if missing:raise HTTPException(409,{'reason':'Required diagnostics incomplete','checks':missing})
    scoring=score(i.definitions,results,i.score_rule_version)
    if scoring['functional_score'] is None:raise HTTPException(409,'No conclusive functional checks')
    cosmetics=evidence_summary(db,i)
    if settings.require_cosmetic and not cosmetics['complete']:
        raise HTTPException(409,{'reason':'Cosmetic evidence incomplete','missing':cosmetics['missing']})
    consume_report_entitlement(db,user,i)
    defs={d['id']:d for d in i.definitions}
    # Public signed projection never includes arbitrary client strings/evidence, identity, coordinates or account information.
    checks=[{'check_id':r['check_id'],'title':defs[r['check_id']]['title'],'category':defs[r['check_id']]['category'],'status':r['status'],
             'definition_version':r['definition_version'],'method':defs[r['check_id']]['mode'],'expected_condition':defs[r['check_id']]['pass_criteria'],
             'completed_at':r['completed_at'],'started_at':r['started_at'],'weight':defs[r['check_id']]['weight'],
             'measured_value':r['measured_value'],'evidence':r['evidence'],'message':r['message'],'app_version':r['app_version']} for r in results]
    device={k:i.device_profile.get(k,'Not provided') for k in ('manufacturer','model','release','api')}
    # Device label length is bounded at input. Labels alone are public; no fingerprint/serial.
    report_id=uid(); finalized=utcnow().isoformat()
    payload={'report_id':report_id,'inspection_id':i.id,'device':device,'finalized_at':finalized,'registry_version':1,'score':scoring,
             'checks':checks,'cosmetic':{'score':cosmetics['cosmetic_score'],'grade':cosmetics['cosmetic_grade'],'rule_version':cosmetics['rule_version'],'method':cosmetics['method'],'review_id':cosmetics['review_id'],'ai_status':cosmetics['ai_status'],
               'findings':[{k:f[k] for k in ('defect','severity','source','confidence')} for f in cosmetics['findings']],
               'evidence_hashes':[x['sha256'] for x in cosmetics['images']]},
             'masked_imei':'***********'+i.imei_last4 if i.imei_last4 else None,'pta_status':i.pta_status,
             'disclaimer':'Observed condition at inspection time only. Platform metadata is not tested functionality. Human confirmations are not laboratory certification. No OEM authenticity, battery lifespan, hidden liquid damage, future reliability or official PTA lookup is claimed.'}
    if i.score_rule_version>=2:
        payload['overall']=overall_score(scoring['functional_score'],cosmetics['cosmetic_score'],i.score_rule_version)
    payload['analysis'] = analyze(results)
    body=canonical(payload); digest=hashlib.sha256(body.encode()).hexdigest(); key=signing_key()
    report=Report(id=report_id,inspection_id=i.id,public_token=secrets.token_urlsafe(24),payload=payload,canonical_json=body,hash=digest,
                  signature=base64.b64encode(key.sign(bytes.fromhex(digest))).decode(),key_version=settings.signing_key_version,
                  public_key=base64.b64encode(key.public_key().public_bytes(serialization.Encoding.Raw,serialization.PublicFormat.Raw)).decode())
    db.add(report);i.status='FINALIZED';i.finalized_at=utcnow()
    audit(db,user.id,'REPORT_SIGNED',i.id,{'report_id':report_id,'hash':digest})
    return report_info(report)


@router.get('/reports')
def reports(user=Depends(current_user),db=Depends(get_db, scope='function')):
    from .models import Inspection
    return [report_info(r,bool(db.get(Revocation,r.id))) for r in db.scalars(select(Report).join(Inspection).where(Inspection.owner_id==user.id).order_by(Report.created_at.desc()).limit(100))]


@router.get('/reports/{report_id}')
def report(report_id:UUID,user=Depends(current_user),db=Depends(get_db, scope='function')):
    r=db.get(Report,str(report_id))
    if not r:raise HTTPException(404,'Report not found')
    owned(db,user,r.inspection_id)
    return report_info(r,bool(db.get(Revocation,r.id)))


def generate_pdf(report,revoked=False):
    import qrcode
    from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, Image, KeepTogether
    from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
    from reportlab.lib import colors
    from reportlab.lib.pagesizes import A4
    output=io.BytesIO();styles=getSampleStyleSheet()
    styles.add(ParagraphStyle(name='SmallText',fontSize=8,leading=11,spaceAfter=4))
    styles['Title'].textColor=colors.HexColor('#123c36')
    styles['Heading2'].keepWithNext=True
    p=report.payload
    doc=SimpleDocTemplate(output,pagesize=A4,rightMargin=36,leftMargin=36,topMargin=40,bottomMargin=40,title='PhoneProof verified condition report')
    def para(value,style='BodyText'):return Paragraph(html.escape(str(value)),styles[style])
    flow=[para('PhoneProof PK','Title'),para('Verified device condition report','Heading2'),para('REVOKED' if revoked else 'Server signature verified' if verify_signature(report) else 'INVALID SIGNATURE','Heading2'),
          para('Report '+report.id,'SmallText'),para(p['finalized_at'],'SmallText'),Spacer(1,10),
          para(p['device']['manufacturer']+' '+p['device']['model'],'Heading1'),para('Android '+p['device']['release']+' / API '+p['device']['api']),
          para('Functional score: '+str(p['score']['functional_score'])+' / 100 | Grade '+str(p['score']['grade']),'Heading2'),
          para('Conclusive weighted coverage: '+str(p['score']['coverage_percent'])+'%. Excluded and incomplete checks remain listed.'),
          para('Cosmetic score: '+str(p['cosmetic']['score'])+(' | Grade '+p['cosmetic']['grade'] if p['cosmetic'].get('grade') else '')+' | '+p['cosmetic']['method'],'Heading2'),
          para('IMEI: '+str(p['masked_imei'] or 'Not provided')+' | PTA: '+p['pta_status']),Spacer(1,10)]
    overall=p.get('overall',{})
    if overall.get('score') is not None:
        flow += [para(f"Overall score: {overall['score']} / 100 | Grade {overall['grade']}",'Heading2'),para(overall['method'],'SmallText')]
    categories=p['score'].get('categories',[])
    if categories:
        flow += [para('Category weighting','Heading2'),
                 para(f"Scoring rule v{p['score']['rule_version']}. Category shares are normalized across categories with conclusive checks. Unavailable checks reduce coverage; they are not passes or failures.",'SmallText')]
        category_rows=[[para(text,'SmallText') for text in ('Category','Share','Score','Coverage')]]
        for category in categories:
            if not category['applicable_weight']:continue
            category_rows.append([para(category['title'],'SmallText'),para(str(category['weight_percent'])+'%','SmallText'),
                                  para('Not measured' if category['score'] is None else str(category['score'])+'/100','SmallText'),
                                  para(str(category['coverage_percent'])+'%','SmallText')])
        category_table=Table(category_rows,colWidths=[238,65,110,110],repeatRows=1,hAlign='LEFT')
        category_table.setStyle(TableStyle([('VALIGN',(0,0),(-1,-1),'TOP'),('BACKGROUND',(0,0),(-1,0),colors.HexColor('#e7f0ec')),('BOTTOMPADDING',(0,0),(-1,-1),6)]))
        flow.append(category_table)
        titles={check['check_id']:check['title'] for check in p['checks']}
        for loss in p['score']['point_losses']:
            flow.append(para(f"{titles.get(loss['check_id'],loss['check_id'])}: {loss['points_lost']} weighted points lost.",'SmallText'))
        for cap in p['score']['caps']:
            flow.append(para(f"{cap['reason']}: maximum functional score {cap['maximum_score']} ({titles.get(cap['check_id'],cap['check_id'])}).",'SmallText'))
    if p['score']['critical_failures']:flow += [para('Critical failures','Heading2'),para(', '.join(p['score']['critical_failures']))]
    if p.get('analysis'):
        flow += [para('Diagnostic interpretation','Heading2'), para('Advisory deterministic policy v' + str(p['analysis']['policy_version']), 'SmallText')]
        for finding in p['analysis']['findings']:
            if finding['severity'] == 'INFO':
                continue
            flow += [para(finding['title'], 'Heading3'), para(finding['interpretation']),
                     para(finding['further_inspection'], 'SmallText'), para(finding['limitations'], 'SmallText')]
    for category in sorted({r['category'] for r in p['checks']}):
        heading=para(category.title(),'Heading2')
        rows=[[para('Individual check','SmallText'),para('Result','SmallText'),para('Method / definition','SmallText')]]
        for r in p['checks']:
            if r['category']==category:
                measured='; '.join(f'{k}: {v}' for k,v in r.get('measured_value',{}).items())
                detail=r['title']+ (' | '+measured if measured else '')
                rows.append([para(detail,'SmallText'),para(r['status'].replace('_',' '),'SmallText'),para(r['method'].replace('_',' ')+' / v'+str(r['definition_version'])+' | '+r['completed_at'],'SmallText')])
        table=Table(rows,colWidths=[235,115,173],repeatRows=1,hAlign='LEFT',splitInRow=1)
        table.setStyle(TableStyle([('BACKGROUND',(0,0),(-1,0),colors.HexColor('#e7f0ec')),('VALIGN',(0,0),(-1,-1),'TOP'),('LINEBELOW',(0,0),(-1,0),0.5,colors.grey),('BOTTOMPADDING',(0,0),(-1,-1),7),('TOPPADDING',(0,0),(-1,-1),7),('ROWBACKGROUNDS',(0,1),(-1,-1),[colors.white,colors.HexColor('#f7f9f8')])]))
        flow.append(KeepTogether([heading,table]))
    flow.append(para('Cosmetic observations','Heading2'))
    flow += [para(f['severity']+' '+f['defect'].replace('_',' ')+' ('+f['source']+')') for f in p['cosmetic']['findings']]
    if not p['cosmetic']['findings']:flow.append(para('No defects annotated in the signed review. AI status: '+p['cosmetic']['ai_status']))
    qr=io.BytesIO();qrcode.make(settings.public_report_base_url.rstrip('/')+'/r/'+report.public_token).save(qr,format='PNG');qr.seek(0)
    verification=Table([[Image(qr,width=90,height=90),[
        para('SHA-256: '+report.hash,'SmallText'),
        para('Ed25519 signature: '+report.signature,'SmallText'),
        para('Signing key version: '+report.key_version,'SmallText')]]],colWidths=[110,413])
    verification.setStyle(TableStyle([('VALIGN',(0,0),(-1,-1),'TOP'),('LEFTPADDING',(0,0),(-1,-1),0)]))
    flow.append(KeepTogether([Spacer(1,12),para('Verify this report','Heading2'),verification,Spacer(1,8),para(p['disclaimer'],'SmallText')]))
    def footer(canvas,doc):
        canvas.saveState();canvas.setFont('Helvetica',8);canvas.setFillColor(colors.grey)
        canvas.drawString(36,22,'PhoneProof PK | '+report.id);canvas.drawRightString(A4[0]-36,22,str(doc.page));canvas.restoreState()
    doc.build(flow,onFirstPage=footer,onLaterPages=footer)
    return output.getvalue()


@router.get('/reports/{report_id}/pdf')
def pdf(report_id:UUID,user=Depends(current_user),db=Depends(get_db, scope='function')):
    r=db.get(Report,str(report_id))
    if not r:raise HTTPException(404,'Report not found')
    owned(db,user,r.inspection_id)
    if not verify_signature(r):raise HTTPException(409,'Report signature is invalid')
    return Response(generate_pdf(r,bool(db.get(Revocation,r.id))),media_type='application/pdf',headers={'Content-Disposition':f'attachment; filename="PhoneProof-{r.id}.pdf"','Cache-Control':'private, no-store'})


@router.get('/verify/{token}')
def public_verify(token:str,db=Depends(get_db, scope='function')):
    r=db.scalar(select(Report).where(Report.public_token==token))
    if not r:raise HTTPException(404,'Report not found')
    info=copy.deepcopy(report_info(r,bool(db.get(Revocation,r.id))))
    # Owner analysis can contain client-supplied free text and telemetry.
    info['payload'].pop('analysis', None)
    for check in info['payload']['checks']:
        for key in ('measured_value','evidence','message'):
            check.pop(key,None)
    info['payload_is_privacy_projection']=True
    return info


@public_router.get('/r/{token}',response_class=HTMLResponse)
def verification_page(token:str,db=Depends(get_db, scope='function')):
    info=public_verify(token,db);p=info['payload'];esc=html.escape
    rows=''.join(f'<tr><td>{esc(c["title"])}</td><td class="{c["status"]}">{esc(c["status"].replace("_"," "))}</td><td>{esc(c["method"].replace("_"," "))}</td></tr>' for c in p['checks'])
    scoring_details=''
    overall=p.get('overall',{})
    if overall.get('score') is not None:
        scoring_details+=f'<p class="score">Overall score {overall["score"]}/100 · Grade {esc(overall["grade"])}</p><p>{esc(overall["method"])}</p>'
    categories=p['score'].get('categories',[])
    if categories:
        category_rows=''.join(f'<tr><td>{esc(c["title"])}</td><td>{c["weight_percent"]}%</td><td>{c["score"] if c["score"] is not None else "Not measured"}</td><td>{c["coverage_percent"]}%</td></tr>' for c in categories if c['applicable_weight'])
        scoring_details+=f'<h2>Category weighting</h2><p>Scoring rule v{p["score"]["rule_version"]}. Shares are normalized across categories with conclusive checks. Unavailable checks reduce coverage.</p><table><thead><tr><th>Category</th><th>Share</th><th>Score</th><th>Coverage</th></tr></thead><tbody>{category_rows}</tbody></table>'
        titles={check['check_id']:check['title'] for check in p['checks']}
        if p['score']['point_losses']:
            scoring_details+='<h2>Points lost</h2><ul>'+''.join(f'<li>{esc(titles.get(loss["check_id"],loss["check_id"]))}: {loss["points_lost"]} weighted points</li>' for loss in p['score']['point_losses'])+'</ul>'
        if p['score']['caps']:
            scoring_details+='<h2>Grade limits</h2><ul>'+''.join(f'<li>{esc(cap["reason"])}: maximum functional score {cap["maximum_score"]}</li>' for cap in p['score']['caps'])+'</ul>'
    page=f'''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>PhoneProof report verification</title>
<style>body{{font:16px system-ui;margin:0;background:#f1f5f3;color:#18342e}}main{{max-width:900px;margin:30px auto;padding:24px;background:white}}h1{{font-size:30px}}.badge{{padding:8px 12px;background:#d7eade;display:inline-block}}.FAIL{{font-weight:bold;color:#a12828}}table{{border-collapse:collapse;width:100%;font-size:14px}}td,th{{text-align:left;padding:12px 6px;border-bottom:1px solid #ddd}}.mono{{font:12px monospace;overflow-wrap:anywhere}}.score{{font-size:23px}}@media(max-width:600px){{main{{margin:0;padding:18px}}td,th{{font-size:12px}}}}</style>
<main><p>PHONEPROOF PK</p><span class="badge">{info['status']}</span><h1>{esc(p['device']['manufacturer'])} {esc(p['device']['model'])}</h1><p>Inspection: {esc(p['finalized_at'])}</p>
<p class="score">Functional {p['score']['functional_score']}/100 · Grade {esc(p['score']['grade'] or 'Not graded')}<br>Cosmetic {p['cosmetic']['score'] if p['cosmetic']['score'] is not None else 'Not graded'}{' · Grade '+esc(p['cosmetic']['grade']) if p['cosmetic'].get('grade') else ''}</p>
<p>Conclusive coverage {p['score']['coverage_percent']}%. Read every unavailable and inconclusive check below.</p><p>PTA: {esc(p['pta_status'])} · IMEI: {esc(p['masked_imei'] or 'Not provided')}</p>
{scoring_details}
<h2>Individual results</h2><table><thead><tr><th>Check</th><th>Result</th><th>Evidence method</th></tr></thead><tbody>{rows}</tbody></table>
<h2>Cryptographic verification</h2><p>Server Ed25519 signature: {'verified' if info['status']!='INVALID_SIGNATURE' else 'INVALID'}. Revocation status is checked live.</p><p class="mono">SHA-256 {info['hash']}</p><p>{esc(p['disclaimer'])}</p><p class="mono">Report {info['id']}</p></main></html>'''
    return HTMLResponse(page,headers={'Content-Security-Policy':"default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'",'X-Content-Type-Options':'nosniff','Cache-Control':'no-store','Referrer-Policy':'no-referrer'})


class RevokeInput(Strict):
    reason:str=Field(min_length=5,max_length=300)


@router.post('/admin/reports/{report_id}/revoke')
def revoke(report_id:UUID,data:RevokeInput,user=Depends(require_admin),db=Depends(get_db, scope='function')):
    r=db.get(Report,str(report_id))
    if not r:raise HTTPException(404,'Report not found')
    if not db.get(Revocation,r.id):
        db.add(Revocation(report_id=r.id,reason=data.reason));audit(db,user.id,'REPORT_REVOKED',r.inspection_id,{'report_id':r.id,'reason':data.reason})
    return {'status':'REVOKED'}
