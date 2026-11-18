package pk.phoneproof.app.data

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import org.json.JSONObject
import java.io.File

/** Offline export of saved observations. It never adds a signature or cloud verification claim. */
object LocalReportPdf {
    fun write(report:LocalReport,directory:File):File {
        val payload=JSONObject(report.payload)
        require(payload.optString("report_type")=="LOCAL_INSPECTION_SUMMARY"&&!payload.optBoolean("server_verified")) {
            "This is not a local inspection summary."
        }
        check(directory.isDirectory||directory.mkdirs()) { "The PDF folder could not be created." }
        val file=File(directory,"PhoneProof-local-${report.payloadHash.take(16)}.pdf")
        val document=PdfDocument()
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(28,35,30);textSize=11f }
        var page:PdfDocument.Page?=null
        var y=0f
        var pageNumber=0
        fun finishPage() {
            page?.let { current ->
                paint.textSize=9f;paint.typeface=Typeface.DEFAULT;paint.color=Color.DKGRAY
                current.canvas.drawText("PhoneProof · Local summary · Not server verified",48f,761f,paint)
                current.canvas.drawText(pageNumber.toString(),550f,761f,paint)
                document.finishPage(current)
            }
            page=null
        }
        fun newPage() {
            finishPage();pageNumber++
            page=document.startPage(PdfDocument.PageInfo.Builder(612,792,pageNumber).create())
            y=48f
        }
        fun wrap(text:String,size:Float,bold:Boolean):List<String> {
            paint.textSize=size;paint.typeface=if(bold)Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            var remaining=text.replace(Regex("[\\p{Cntrl}]")," ").trim()
            val lines=mutableListOf<String>()
            while(remaining.isNotEmpty()) {
                var count=paint.breakText(remaining,true,516f,null).coerceAtLeast(1)
                if(count<remaining.length) {
                    val space=remaining.lastIndexOf(' ',count-1)
                    if(space>0)count=space
                }
                lines.add(remaining.take(count))
                remaining=remaining.drop(count).trimStart()
            }
            return lines.ifEmpty { listOf("") }
        }
        fun drawLines(lines:List<String>,size:Float,bold:Boolean) {
            for(text in lines) {
                if(page==null||y+size+6f>735f)newPage()
                paint.textSize=size;paint.typeface=if(bold)Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                paint.color=Color.rgb(28,35,30)
                page!!.canvas.drawText(text,48f,y+size,paint)
                y+=size+6f
            }
        }
        fun line(text:String,size:Float=11f,bold:Boolean=false) = drawLines(wrap(text,size,bold),size,bold)
        try {
            line("PhoneProof",23f,true)
            line("LOCAL INSPECTION SUMMARY",12f,true)
            line("Not server verified. Human assessments are recorded as reported.",10f)
            line("")
            val device=payload.getJSONObject("device")
            val deviceName=listOf(device.optString("manufacturer"),device.optString("model"))
                .filter { it.isNotBlank()&&it!="Unavailable" }.joinToString(" ").ifBlank { "Phone" }
            line(deviceName,16f,true)
            line("Android ${device.optString("release")} · API ${device.optString("api")}")
            line("Started: ${payload.optString("inspection_started_at")}")
            line("Saved: ${payload.optString("saved_at")}")
            line("Inspection: ${report.inspectionId}",9f)
            line("")
            line("Functional checks",14f,true)
            line("${payload.optInt("passed")} passed · ${payload.optInt("problems")} need attention · ${payload.optInt("unverified")} unverified · ${payload.optInt("unavailable")} unavailable")
            if(!payload.isNull("tested_coverage_percent"))line("Conclusive coverage: ${"%.1f".format(java.util.Locale.ROOT,payload.optDouble("tested_coverage_percent"))}%")
            if(!payload.isNull("functional_score_preview"))line("Local score preview: ${"%.1f".format(java.util.Locale.ROOT,payload.optDouble("functional_score_preview"))}/100 · Rule ${payload.optInt("score_rule_version")}")
            line("A partial inspection can leave defects unobserved. This summary is not a laboratory certificate.",10f)
            payload.optJSONObject("analysis")?.let { analysis ->
                line("Diagnostic interpretation · Policy ${analysis.optInt("policy_version")}",14f,true)
                val findings = analysis.getJSONArray("findings")
                for(index in 0 until findings.length()) {
                    val finding = findings.getJSONObject(index)
                    if(finding.optString("severity") == "INFO") continue
                    line(finding.getString("title"),11f,true)
                    line(finding.getString("interpretation"),10f)
                    line(finding.getString("further_inspection"),10f)
                    line(finding.getString("limitations"),9f)
                }
            }
            line("")
            val checks=payload.getJSONArray("checks")
            for(index in 0 until checks.length()) {
                val check=checks.getJSONObject(index)
                if(!check.optBoolean("score_eligible",true))continue
                val status=check.optString("status")
                val title=wrap("${check.optString("title")} · ${statusLabel(status)}",11f,true)
                val detail=wrap("${check.optString("id")} · definition ${check.optInt("definition_version")} · ${check.optString("method").lowercase().replace('_',' ')}",9f,false)
                val message=check.optString("message").takeIf { status!="NOT_TESTED"&&it.isNotBlank() }
                    ?.let { wrap(it,10f,false) }.orEmpty()
                val height=title.size*17f+detail.size*15f+message.size*16f+5f
                if(height<=687f&&y+height>735f)newPage()
                drawLines(title,11f,true)
                drawLines(detail,9f,false)
                drawLines(message,10f,false)
                y+=5f
            }
            finishPage()
            file.outputStream().use { document.writeTo(it) }
            return file
        } catch(error:Exception) {
            file.delete();throw error
        } finally {
            page?.let { runCatching { document.finishPage(it) } }
            document.close()
        }
    }

    fun statusLabel(status:String):String=when(status) {
        "PASS"->"Passed"
        "FAIL"->"Needs attention"
        "NOT_SUPPORTED"->"Not supported"
        "NOT_ACCESSIBLE"->"Not accessible"
        "SKIPPED_BY_USER"->"Skipped"
        "INCONCLUSIVE"->"Inconclusive"
        else->"Not tested"
    }
}
