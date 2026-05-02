package kr.ac.kopo.talkti

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api // 추가
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val KakaoYellow = Color(0xFFFEE500)
private val Background = Color(0xFFFFFDF6)
private val TextPrimary = Color(0xFF202124)
private val TextSecondary = Color(0xFF5F6368)

@Composable
fun App(
    onStartSetupClick: () -> Unit = {}
) {
    MaterialTheme {
        TalkTiHomeScreen(
            onStartSetupClick = onStartSetupClick
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class) // 경고 해결을 위해 추가
@Composable
private fun TalkTiHomeScreen(onStartSetupClick: () -> Unit) {
    val context = LocalContext.current
    val sharedPref = remember { context.getSharedPreferences("talkti_prefs", Context.MODE_PRIVATE) }

    var serverUrl by remember {
        mutableStateOf(sharedPref.getString("server_url", "http://10.0.2.2:8080") ?: "http://10.0.2.2:8080")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .safeContentPadding()
            .padding(horizontal = 28.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "TalkTi",
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "어려운 앱 화면을\n음성으로 쉽게 안내해드릴게요.",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            textAlign = TextAlign.Center,
            lineHeight = 32.sp
        )

        Spacer(modifier = Modifier.height(20.dp))

        OutlinedTextField(
            value = serverUrl,
            onValueChange = {
                serverUrl = it
                sharedPref.edit().putString("server_url", it).apply()
            },
            label = { Text("서버 주소 (IP:Port)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "사용 전 준비",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "1. 마이크 권한을 허용해주세요.\n2. 접근성 설정에서 TalkTi를 켜주세요.\n3. 카카오 앱 위에 뜨는 버튼을 눌러 말해주세요.",
                    fontSize = 17.sp,
                    color = TextSecondary,
                    lineHeight = 27.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        Button(
            onClick = onStartSetupClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = KakaoYellow,
                contentColor = Color.Black
            )
        ) {
            Text(
                text = "TalkTi 접근성 설정 열기",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}