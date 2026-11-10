"""Private payment receipt snapshots. No claim of tax invoice compliance."""
import html
import io
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import Response
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle
from sqlalchemy import select

from .auth import current_user
from .billing import Transaction, PaymentReceipt
from .db import get_db

router = APIRouter(tags=['Billing'])


def owned_receipt(db, user, transaction_id):
    transaction = db.get(Transaction, str(transaction_id))
    if not transaction or transaction.user_id != user.id:
        raise HTTPException(404, 'Receipt not found')
    receipt = db.scalar(select(PaymentReceipt).where(PaymentReceipt.transaction_id == transaction.id))
    if not receipt:
        raise HTTPException(409, 'Receipt is available only after confirmed settlement; legacy records may require support')
    return receipt


@router.get('/transactions/{transaction_id}/receipt')
def receipt(transaction_id: UUID, user=Depends(current_user), db=Depends(get_db, scope='function')):
    item = owned_receipt(db, user, transaction_id)
    return {'id': item.id, 'document': item.document, 'issued_at': item.created_at}


def receipt_pdf(item):
    buffer = io.BytesIO()
    document = SimpleDocTemplate(buffer, pagesize=A4, rightMargin=18*mm, leftMargin=18*mm,
        topMargin=18*mm, bottomMargin=18*mm, title='PhoneProof payment receipt', author='PhoneProof PK', invariant=1)
    styles = getSampleStyleSheet()
    data = item.document
    def text(value):
        return Paragraph(html.escape(str(value if value is not None else 'Not supplied')), styles['BodyText'])
    content = [Paragraph('PhoneProof PK — Payment receipt', styles['Title']), Spacer(1, 7*mm)]
    if data['development']:
        content += [Paragraph('DEVELOPMENT PAYMENT — NOT REAL MONEY', styles['Heading2']), Spacer(1, 4*mm)]
    rows = [(label, data.get(key)) for label, key in [
        ('Transaction ID', 'transaction_id'), ('Customer', 'customer'), ('Purchased item', 'description'),
        ('Amount', 'amount'), ('Currency', 'currency'), ('Confirmed at (UTC)', 'paid_at'),
        ('Payment method', 'payment_method'), ('Provider reference', 'provider_reference'),
        ('Inspection', 'inspection_id')]]
    rows.insert(0, ('Receipt ID', item.id))
    table = Table([[text(k), text(v)] for k, v in rows], colWidths=[43*mm, 125*mm], splitInRow=1)
    table.setStyle(TableStyle([('BACKGROUND', (0,0), (0,-1), colors.HexColor('#e9f1ee')),
        ('VALIGN', (0,0), (-1,-1), 'TOP'), ('BOX', (0,0), (-1,-1), .5, colors.HexColor('#bacbc3')),
        ('INNERGRID', (0,0), (-1,-1), .3, colors.HexColor('#bacbc3')),
        ('TOPPADDING', (0,0), (-1,-1), 8), ('BOTTOMPADDING', (0,0), (-1,-1), 8)]))
    content += [table, Spacer(1, 7*mm), text('Status at issue: PAID. This receipt records the original settlement. '
        'Check transaction history for subsequent refunds. This document is not a tax invoice.')]
    document.build(content)
    return buffer.getvalue()


@router.get('/transactions/{transaction_id}/receipt/pdf')
@router.get('/invoices/{transaction_id}/pdf')
def download_receipt(transaction_id: UUID, user=Depends(current_user), db=Depends(get_db, scope='function')):
    item = owned_receipt(db, user, transaction_id)
    return Response(receipt_pdf(item), media_type='application/pdf', headers={
        'Cache-Control': 'no-store', 'Content-Disposition': f'attachment; filename="PhoneProof-receipt-{item.id}.pdf"'})
