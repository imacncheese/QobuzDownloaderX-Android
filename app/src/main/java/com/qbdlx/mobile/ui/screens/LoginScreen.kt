package com.qbdlx.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qbdlx.mobile.R
import com.qbdlx.mobile.ui.AppViewModel

@Composable
fun LoginScreen(vm: AppViewModel) {
    val state by vm.auth.collectAsStateWithLifecycle()
    var passwordVisible by remember { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(44.dp),
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.login_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.login_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(28.dp))

            Card(modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    if (state.useToken) {
                        OutlinedTextField(
                            value = state.token,
                            onValueChange = vm::onToken,
                            label = { Text(stringResource(R.string.login_token)) },
                            leadingIcon = { Icon(Icons.Filled.Key, contentDescription = null) },
                            singleLine = true,
                            enabled = !state.busy,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        OutlinedTextField(
                            value = state.email,
                            onValueChange = vm::onEmail,
                            label = { Text(stringResource(R.string.login_email)) },
                            singleLine = true,
                            enabled = !state.busy,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Next,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = state.password,
                            onValueChange = vm::onPassword,
                            label = { Text(stringResource(R.string.login_password)) },
                            singleLine = true,
                            enabled = !state.busy,
                            visualTransformation = if (passwordVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        imageVector = if (passwordVisible) {
                                            Icons.Filled.VisibilityOff
                                        } else {
                                            Icons.Filled.Visibility
                                        },
                                        contentDescription = null,
                                    )
                                }
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Spacer(Modifier.height(18.dp))

                    Button(
                        onClick = vm::signIn,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.height(0.dp))
                        } else {
                            Text(stringResource(R.string.login_button))
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    TextButton(onClick = vm::toggleTokenMode, enabled = !state.busy) {
                        Text(
                            stringResource(
                                if (state.useToken) R.string.login_with_password
                                else R.string.login_with_token
                            )
                        )
                    }
                    TextButton(onClick = vm::toggleAdvanced, enabled = !state.busy) {
                        Text(stringResource(R.string.login_advanced))
                    }

                    if (state.showAdvanced) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.login_credentials_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = state.appId,
                            onValueChange = vm::onAppId,
                            label = { Text(stringResource(R.string.login_app_id)) },
                            singleLine = true,
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = state.appSecret,
                            onValueChange = vm::onAppSecret,
                            label = { Text(stringResource(R.string.login_app_secret)) },
                            singleLine = true,
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            state.error?.let { err ->
                Spacer(Modifier.height(16.dp))
                Card(
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = stringResource(R.string.login_error),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.settings_about_body).substringBefore("\n\n"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
