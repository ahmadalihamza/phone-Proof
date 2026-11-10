from datetime import datetime
from fastapi import APIRouter,Depends,HTTPException
from fastapi.responses import HTMLResponse
from sqlalchemy import select,func
from pydantic import Field
from .db import get_db,utcnow
from .auth import require_admin
from .models import User,Inspection,Report,Audit
from .billing import Plan,Subscription,Transaction,Refund,Promo,PaymentAttempt
from .inspections import Strict
from .audit import audit

router=APIRouter(prefix='/api/v1/admin',tags=['Administration'])
page_router=APIRouter()

@router.get('/overview')
def overview(user=Depends(require_admin),db=Depends(get_db, scope='function')):
    return {'users':db.scalar(select(func.count()).select_from(User)),
            'inspections':db.scalar(select(func.count()).select_from(Inspection)),
            'signed_reports':db.scalar(select(func.count()).select_from(Report)),
            'active_subscriptions':db.scalar(select(func.count()).select_from(Subscription).where(Subscription.status=='ACTIVE',Subscription.current_period_end>utcnow())),
            'paid_total_pkr':str(db.scalar(select(func.coalesce(func.sum(Transaction.amount),0)).where(Transaction.status=='PAID',Transaction.currency=='PKR',Transaction.provider!='development'))),
            'development_paid_total_pkr':str(db.scalar(select(func.coalesce(func.sum(Transaction.amount),0)).where(Transaction.status=='PAID',Transaction.currency=='PKR',Transaction.provider=='development'))),
            'development_payment_total_is_not_revenue':True}

@router.get('/users')
def users(q:str='',user=Depends(require_admin),db=Depends(get_db, scope='function')):
    return [{'id':u.id,'email':u.email,'role':u.role,'created_at':u.created_at} for u in db.scalars(select(User).where(User.email.contains(q[:100],autoescape=True)).limit(100))]

@router.get('/billing')
def billing(user=Depends(require_admin),db=Depends(get_db, scope='function')):
    return {'transactions':[{'id':t.id,'user_id':t.user_id,'amount':str(t.amount),'currency':t.currency,'status':t.status,'provider':t.provider,'provider_reference':t.provider_reference,'fulfillment_status':t.fulfillment_status,'created_at':t.created_at} for t in db.scalars(select(Transaction).order_by(Transaction.created_at.desc()).limit(200))],
            'payment_attempts':[{'transaction_id':a.transaction_id,'provider':a.provider,'status':a.status,'verification_count':a.verification_count,'last_checked_at':a.last_checked_at,'last_error':a.last_error} for a in db.scalars(select(PaymentAttempt).order_by(PaymentAttempt.created_at.desc()).limit(200))],
            'refunds':[{'id':r.id,'transaction_id':r.transaction_id,'amount':str(r.amount),'status':r.status,'reason':r.reason} for r in db.scalars(select(Refund).order_by(Refund.created_at.desc()).limit(100))],
            'subscriptions':[{'id':s.id,'user_id':s.user_id,'plan_id':s.plan_id,'status':s.status,'expires':s.current_period_end} for s in db.scalars(select(Subscription).limit(200))]}

@router.get('/audit')
def audit_events(user=Depends(require_admin),db=Depends(get_db, scope='function')):
    return [{'id':a.id,'event':a.event,'actor_id':a.actor_id,'inspection_id':a.inspection_id,'created_at':a.created_at,'data':a.data} for a in db.scalars(select(Audit).order_by(Audit.created_at.desc()).limit(200))]

class PromoConfig(Strict):
    code:str=Field(pattern=r'^[A-Z0-9_-]{3,40}$')
    max_redemptions:int=Field(ge=1,le=100000)
    expires_at:datetime
    active:bool=True

@router.post('/promotions')
def promotion(data:PromoConfig,user=Depends(require_admin),db=Depends(get_db, scope='function')):
    p=db.get(Promo,data.code)
    if not p:p=Promo(**data.model_dump());db.add(p)
    else:
        for key,value in data.model_dump().items():setattr(p,key,value)
    audit(db,user.id,'PROMOTION_CONFIGURED',data={'code':p.code})
    return {'code':p.code}

@page_router.get('/admin',response_class=HTMLResponse)
def admin_page():
    # Tokens stay in memory and are discarded on reload. Every endpoint independently enforces RBAC/MFA.
    return HTMLResponse('''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>PhoneProof administration</title>
<style>body{font:16px system-ui;max-width:1000px;margin:32px auto;padding:20px;background:#f5f7f6;color:#183c32}input,button,textarea,select{font:inherit;padding:10px;margin:5px}button{cursor:pointer}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:white;padding:20px}section{border-top:1px solid #bbb;margin-top:20px;padding-top:12px}label{display:block}textarea{width:90%;height:110px}.error{color:#ac2727}</style>
<h1>PhoneProof administration</h1><p>Authenticated operations and audit history. No payment provider is active until configured.</p>
<form id="login"><input id="email" type="email" placeholder="Admin email" required><input id="password" type="password" placeholder="Password" required><input id="otp" inputmode="numeric" placeholder="Authenticator code"><button>Sign in</button></form><p id="notice"></p>
<div id="panel" hidden><button id="logout">Sign out</button><section><h2>Read data</h2><select id="readpath"><option value="overview">Overview</option><option value="billing">Billing</option><option value="audit">Audit history</option><option value="users">Users</option></select><button id="load">Load</button><pre id="data"></pre></section>
<section><h2>Configure plan or promotion</h2><select id="configpath"><option value="plans">Plan</option><option value="promotions">Promotion</option></select><p>Plan fields: id, name, price (decimal string), report_limit, period_days, active. Promotion fields: code, max_redemptions, expires_at (ISO time), active.</p><textarea id="configjson" placeholder="Enter the complete configuration JSON"></textarea><button id="save">Validate & save</button></section>
<section><h2>Revoke a report</h2><input id="reportid" placeholder="Report UUID"><input id="reason" placeholder="Reason (required)"><button id="revoke">Revoke report</button></section>
<section><h2>Review a refund</h2><p>Development settlements only until a real provider adapter is installed.</p><input id="refundid" placeholder="Refund UUID"><button id="refund">Approve refund</button><input id="refundreason" placeholder="Reason for rejection"><button id="rejectrefund">Reject refund</button></section></div>
<script>
let token=null;const $=id=>document.getElementById(id);const notice=t=>{$('notice').textContent=t};
async function call(path,body){let response=await fetch('/api/v1/'+path,{method:body?'POST':'GET',headers:{'Content-Type':'application/json',...(token?{'Authorization':'Bearer '+token}:{})},...(body?{body:JSON.stringify(body)}:{})});let data=await response.json();if(!response.ok)throw Error(JSON.stringify(data.detail||data));return data}
function action(fn){return async e=>{e.preventDefault();try{await fn();}catch(err){notice(err.message)}}}
$('login').onsubmit=action(async()=>{let body={email:$('email').value,password:$('password').value};if($('otp').value)body.otp=$('otp').value;let data=await call('auth/login',body);token=data.access_token;await call('admin/overview');$('panel').hidden=false;$('password').value='';$('otp').value='';notice('Signed in. Access expires in fifteen minutes.');});
$('logout').onclick=()=>{token=null;$('panel').hidden=true;notice('Signed out');};
$('load').onclick=action(async()=>{$('data').textContent=JSON.stringify(await call('admin/'+$('readpath').value),null,2)});
$('save').onclick=action(async()=>{let result=await call('admin/'+$('configpath').value,JSON.parse($('configjson').value));notice('Saved '+JSON.stringify(result))});
$('revoke').onclick=action(async()=>{if(!confirm('Revoke this signed report? It will remain visible as revoked.'))return;await call('admin/reports/'+encodeURIComponent($('reportid').value)+'/revoke',{reason:$('reason').value});notice('Report revoked')});
$('refund').onclick=action(async()=>{await call('admin/refunds/'+encodeURIComponent($('refundid').value)+'/approve',{});notice('Refund settled')});
$('rejectrefund').onclick=action(async()=>{await call('admin/refunds/'+encodeURIComponent($('refundid').value)+'/reject',{reason:$('refundreason').value});notice('Refund rejected')});
</script></html>''',headers={'Cache-Control':'no-store','X-Content-Type-Options':'nosniff','Content-Security-Policy':"default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; frame-ancestors 'none'"})
