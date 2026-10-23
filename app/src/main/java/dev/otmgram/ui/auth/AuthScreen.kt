package dev.otmgram.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.otmgram.data.AuthState
import dev.otmgram.data.CodeDelivery

/** Login flow; shown for every [AuthState] except [AuthState.Ready]. */
@Composable
fun AuthScreen(state: AuthState, viewModel: AuthViewModel) {
    when (state) {
        AuthState.Loading, AuthState.Ready -> LoadingScreen()
        AuthState.WaitPhoneNumber -> PhoneNumberStep(viewModel)
        is AuthState.WaitCode -> CodeStep(state, viewModel)
        is AuthState.WaitPassword -> PasswordStep(state, viewModel)
        AuthState.WaitRegistration -> RegistrationStep(viewModel)
        is AuthState.Unsupported -> UnsupportedStep(state, viewModel)
    }
}

@Composable
fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun PhoneNumberStep(viewModel: AuthViewModel) {
    var phone by rememberSaveable { mutableStateOf("") }
    val submit = { viewModel.submitPhoneNumber(phone) }
    AuthStep(
        title = "Your phone number",
        description = "In international format, e.g. +49 151 23456789.",
        viewModel = viewModel,
        submitLabel = "Next",
        canSubmit = phone.isNotBlank(),
        onSubmit = submit,
    ) {
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it },
            label = { Text("Phone number") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().testTag("phone"),
        )
    }
}

@Composable
private fun CodeStep(state: AuthState.WaitCode, viewModel: AuthViewModel) {
    var code by rememberSaveable { mutableStateOf("") }
    val submit = { viewModel.submitCode(code) }
    LaunchedEffect(code) {
        if (state.length != null && code.length == state.length) submit()
    }
    val sentTo = when (state.delivery) {
        CodeDelivery.TelegramApp -> "to your Telegram app on another device"
        CodeDelivery.Sms -> "by SMS to ${state.phoneNumber}"
        CodeDelivery.Call -> "by a phone call to ${state.phoneNumber}"
        CodeDelivery.Other -> "to ${state.phoneNumber}"
    }
    AuthStep(
        title = "Enter the code",
        description = "We've sent the code $sentTo.",
        viewModel = viewModel,
        submitLabel = "Next",
        canSubmit = code.isNotBlank(),
        onSubmit = submit,
        secondaryActions = {
            TextButton(onClick = viewModel::resendCode, enabled = !viewModel.busy) { Text("Resend code") }
            TextButton(onClick = viewModel::startOver, enabled = !viewModel.busy) { Text("Wrong number? Start over") }
        },
    ) {
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter(Char::isDigit) },
            label = { Text("Code") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().testTag("code"),
        )
    }
}

@Composable
private fun PasswordStep(state: AuthState.WaitPassword, viewModel: AuthViewModel) {
    var password by rememberSaveable { mutableStateOf("") }
    val submit = { viewModel.submitPassword(password) }
    AuthStep(
        title = "Two-step verification",
        description = "Your account is protected with an additional password.",
        viewModel = viewModel,
        submitLabel = "Log in",
        canSubmit = password.isNotEmpty(),
        onSubmit = submit,
        secondaryActions = {
            TextButton(onClick = viewModel::startOver, enabled = !viewModel.busy) { Text("Start over") }
        },
    ) {
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            supportingText = state.hint.takeIf { it.isNotEmpty() }?.let { { Text("Hint: $it") } },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().testTag("password"),
        )
    }
}

@Composable
private fun RegistrationStep(viewModel: AuthViewModel) {
    var firstName by rememberSaveable { mutableStateOf("") }
    var lastName by rememberSaveable { mutableStateOf("") }
    AuthStep(
        title = "Your name",
        description = "There's no Telegram account with this number yet. Enter a name to create one.",
        viewModel = viewModel,
        submitLabel = "Create account",
        canSubmit = firstName.isNotBlank(),
        onSubmit = { viewModel.register(firstName, lastName) },
        secondaryActions = {
            TextButton(onClick = viewModel::startOver, enabled = !viewModel.busy) { Text("Start over") }
        },
    ) {
        OutlinedTextField(
            value = firstName,
            onValueChange = { firstName = it },
            label = { Text("First name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("firstName"),
        )
        OutlinedTextField(
            value = lastName,
            onValueChange = { lastName = it },
            label = { Text("Last name (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("lastName"),
        )
    }
}

@Composable
private fun UnsupportedStep(state: AuthState.Unsupported, viewModel: AuthViewModel) {
    AuthStep(
        title = "Can't continue here",
        description = "Telegram asks for ${state.step}, which Otmgram doesn't support. " +
            "Log in with the official Telegram app once, then try again.",
        viewModel = viewModel,
        submitLabel = "Start over",
        canSubmit = true,
        onSubmit = viewModel::startOver,
    ) {}
}

@Composable
private fun AuthStep(
    title: String,
    description: String,
    viewModel: AuthViewModel,
    submitLabel: String,
    canSubmit: Boolean,
    onSubmit: () -> Unit,
    secondaryActions: @Composable ColumnScope.() -> Unit = {},
    fields: @Composable ColumnScope.() -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(description, style = MaterialTheme.typography.bodyMedium)
            fields()
            viewModel.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("error"))
            }
            Button(
                onClick = onSubmit,
                enabled = canSubmit && !viewModel.busy,
                modifier = Modifier.fillMaxWidth().testTag("submit"),
            ) {
                if (viewModel.busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(submitLabel)
                }
            }
            secondaryActions()
        }
    }
}
