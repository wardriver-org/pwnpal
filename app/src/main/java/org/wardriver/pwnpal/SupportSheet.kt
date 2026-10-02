package org.wardriver.pwnpal

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

private const val BTC="bc1qtntclt0ws3zwwuw2nxa3t6yfek3m9vwh4m0z2s"
private const val XMR="82nsqhBv7g9eqPV2x4Gbu21zYrTgw7yCyY55zDbqiJs9LJW7LYByzT9ASd2Xpr4mSKbX6Z3yUJoZ5NH7ARL7URBnGqdiKG7"
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SupportSheet(dismiss:()->Unit) {
    var monero by remember{mutableStateOf(false)}
    var feedback by remember{mutableStateOf("")}
    val address=if(monero)XMR else BTC
    val context=LocalContext.current
    val clipboard=LocalClipboardManager.current
    ModalBottomSheet(onDismissRequest=dismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("Support PwnPal",style=MaterialTheme.typography.headlineSmall)
            Text("Optional contributions help with development and infrastructure. Thank you for using PwnPal.")
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(selected=!monero,onClick={monero=false;feedback=""},label={Text("Bitcoin")})
                FilterChip(selected=monero,onClick={monero=true;feedback=""},label={Text("Monero")})
            }
            Image(painterResource(if(monero)R.drawable.donation_xmr else R.drawable.donation_btc),"${if(monero)"Monero" else "Bitcoin"} donation QR",Modifier.size(220.dp).background(Color.White).align(Alignment.CenterHorizontally))
            SelectionContainer {Text(address,fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)}
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={clipboard.setText(AnnotatedString(address));feedback="Address copied"}){Text("Copy address")}
                TextButton(onClick={try{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("${if(monero)"monero" else "bitcoin"}:$address")))}catch(_:ActivityNotFoundException){feedback="No compatible wallet found. Copy the address instead."}}){Text("Open wallet")}
            }
            if(feedback.isNotEmpty())Text(feedback,style=MaterialTheme.typography.bodySmall)
        }
    }
}
