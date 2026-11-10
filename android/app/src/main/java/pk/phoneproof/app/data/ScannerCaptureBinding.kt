package pk.phoneproof.app.data

/** A delayed camera callback must never borrow the screen's newer account, view or pairing. */
data class ScannerCaptureBinding(
    val generation:Long,val origin:String,val ownerId:String,val inspectionId:String,
    val nonce:String,val scannerId:String,val view:String
) {
    fun isCurrent(session:SessionSnapshot,currentOrigin:String,currentOwner:String?,currentInspection:String?,currentNonce:String?):Boolean =
        session.tokens!=null&&session.generation==generation&&currentOrigin==origin&&currentOwner==ownerId&&
            currentInspection==inspectionId&&currentNonce==nonce
}
