package pk.phoneproof.app.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import pk.phoneproof.app.InspectionViewModel
import pk.phoneproof.app.data.Api
import pk.phoneproof.app.data.AuthenticatedRequestScope

/** Token rotation keeps the same identity; login, logout and a server change replace it. */
internal data class ScreenSession(val generation:Long,val origin:String,val signedIn:Boolean) {
    fun requireCurrent(vm:InspectionViewModel) {
        AuthenticatedRequestScope(generation,origin).requireSession(vm.api.vault.snapshot(),vm.api.baseUrl)
    }
    fun api(context:Context)=Api(context,AuthenticatedRequestScope(generation,origin))
}

@Composable
internal fun rememberScreenSession(vm:InspectionViewModel):ScreenSession {
    val context=LocalContext.current
    fun current():ScreenSession {
        val snapshot=vm.api.vault.snapshot()
        return ScreenSession(snapshot.generation,vm.api.baseUrl,snapshot.tokens!=null)
    }
    var session by remember(vm) { mutableStateOf(current()) }
    DisposableEffect(vm,context) {
        val auth=context.getSharedPreferences("auth",Context.MODE_PRIVATE)
        val connection=context.getSharedPreferences("connection",Context.MODE_PRIVATE)
        val listener=SharedPreferences.OnSharedPreferenceChangeListener { _,_ -> session=current() }
        auth.registerOnSharedPreferenceChangeListener(listener)
        connection.registerOnSharedPreferenceChangeListener(listener)
        session=current()
        onDispose {
            auth.unregisterOnSharedPreferenceChangeListener(listener)
            connection.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
    return session
}

@Composable
internal fun ScreenColumn(content:@Composable ColumnScope.()->Unit) {
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal=24.dp,vertical=20.dp),
        verticalArrangement=Arrangement.spacedBy(20.dp),content=content)
}

@Composable
internal fun ScreenHeading(title:String,subtitle:String?=null) {
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(title,style=MaterialTheme.typography.headlineMedium)
        subtitle?.let { Text(it,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
internal fun QuietCard(modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit) {
    Surface(modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
    }
}

@Composable
internal fun DetailRow(title:String,subtitle:String?=null,enabled:Boolean=true,onClick:()->Unit) {
    TextButton(onClick=onClick,enabled=enabled,modifier=Modifier.fillMaxWidth(),contentPadding=PaddingValues(vertical=12.dp,horizontal=0.dp)) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
            Text(title,Modifier.fillMaxWidth(),style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.onSurface)
            subtitle?.let { Text(it,Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text("›",style=MaterialTheme.typography.titleLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailSheet(title:String,onDismiss:()->Unit,content:@Composable ColumnScope.()->Unit) {
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),
        containerColor=MaterialTheme.colorScheme.background,shape=RoundedCornerShape(topStart=20.dp,topEnd=20.dp)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).verticalScroll(rememberScrollState()).imePadding()
            .padding(horizontal=24.dp).padding(bottom=28.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text(title,Modifier.weight(1f).padding(top=8.dp),style=MaterialTheme.typography.headlineSmall)
                TextButton(onClick=onDismiss) { Text("Close") }
            }
            content()
        }
    }
}

internal fun readableLabel(value:String)=value.lowercase().replace('_',' ').replaceFirstChar { it.titlecase() }
