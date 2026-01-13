package com.ciandt.camerastreaming

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ciandt.camerastreaming.ui.theme.RTMPDemoTheme

class LaunchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RTMPDemoTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    LaunchContent(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun LaunchContent(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Button that opens MainActivity
        Button(onClick = {
            val intent = Intent(context, MainActivity::class.java)
            context.startActivity(intent)
        }) {
            Text(text = "PoC Stream RTMP AWS")
        }
        // Second button: opens the YouTube/RTMPS activity
        Button(onClick = {
            val intent = Intent(context, MainActivityYoutube::class.java)
            context.startActivity(intent)
        }, modifier = Modifier.padding(top = 16.dp)) {
            Text(text = "PoC stream RTMPS Youtube")
        }
        // Second button: opens the YouTube/RTMPS activity
        Button(onClick = {
            val intent = Intent(context, MainActivityYoutubeE2EE::class.java)
            context.startActivity(intent)
        }, modifier = Modifier.padding(top = 16.dp)) {
            Text(text = "PoC stream RTMPS Youtube with E2EE")
        }

    }
}

@Preview(showBackground = true)
@Composable
fun LaunchPreview() {
    RTMPDemoTheme {
        LaunchContent()
    }
}