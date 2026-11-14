package com.example.tvremoteime.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Android TV에서 커스텀 InputMethodService를 활성화하고,
 * 리모컨으로 텍스트 인풋 포커스 시 모바일 화면과의 실시간 양방향 동기화를 바로 테스트할 수 있는 화면입니다.
 */
class TvImeSetupActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var sampleText1 by remember { mutableStateOf("넷플릭스 오징어게임 시즌2") }
                    var sampleText2 by remember { mutableStateOf("https://www.youtube.com") }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(36.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        Text(
                            text = "TV 모바일 연동 키보드 (mDNS + 실시간 텍스트 싱크)",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )

                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "1단계: TV 시스템 키보드 설정 (선택 사항)",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "• 기본 Android TV OS 프로토콜(_androidtvremote2._tcp)을 사용할 경우 별도 설정 없이 모바일 앱에서 바로 연결 가능합니다.\n" +
                                        "• 모든 서드파티 TV 앱에서 커스텀 InputMethodService(_tvkbd._tcp)로 포커스를 감지하려면 아래 버튼으로 'TV 모바일 연동 키보드'를 활성화하세요.",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button(
                                        onClick = {
                                            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                                        }
                                    ) {
                                        Text("1. 키보드 목록에서 활성화")
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                            imm.showInputMethodPicker()
                                        }
                                    ) {
                                        Text("2. 현재 기본 키보드로 선택")
                                    }
                                }
                            }
                        }

                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Text(
                                    text = "2단계: 리모컨으로 아래 텍스트 인풋에 포커스해보세요",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )

                                OutlinedTextField(
                                    value = sampleText1,
                                    onValueChange = { sampleText1 = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("TV 테스트 입력창 1 (검색어)") },
                                    singleLine = true
                                )

                                OutlinedTextField(
                                    value = sampleText2,
                                    onValueChange = { sampleText2 = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("TV 테스트 입력창 2 (URL / 메모)") },
                                    singleLine = true
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
