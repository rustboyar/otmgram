package dev.otmgram.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.otmgram.data.AuthState
import dev.otmgram.data.CodeDelivery
import dev.otmgram.ui.chat.formatDuration
import dev.otmgram.ui.theme.Hero
import dev.otmgram.ui.theme.IconBtn
import dev.otmgram.ui.theme.Otm
import dev.otmgram.ui.theme.OtmField
import dev.otmgram.ui.theme.OtmIcons
import dev.otmgram.ui.theme.OtmType
import dev.otmgram.ui.theme.PrimaryButton
import dev.otmgram.ui.theme.Wordmark
import dev.otmgram.ui.theme.paper
import kotlinx.coroutines.delay

/** Login flow; shown for every [AuthState] except [AuthState.Ready]. */
@Composable
fun AuthScreen(state: AuthState, viewModel: AuthViewModel) {
    when (state) {
        AuthState.Loading, AuthState.Ready -> LoadingScreen()
        AuthState.WaitPhoneNumber -> PhoneNumberStep(viewModel)
        is AuthState.WaitCode -> CodeStep(state, viewModel)
        is AuthState.WaitPassword -> PasswordStep(state, viewModel)
        is AuthState.Unsupported -> UnsupportedStep(state, viewModel)
    }
}

@Composable
fun LoadingScreen(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp), color = Otm.Slate, strokeWidth = 2.5.dp)
    }
}

@Composable
private fun PhoneNumberStep(viewModel: AuthViewModel) {
    var phone by rememberSaveable { mutableStateOf("") }
    val submit = { viewModel.submitPhoneNumber(phone) }
    AuthStep(
        heroHeight = 300,
        wordmark = true,
        title = "Sign in",
        description = "Enter the phone number of your Telegram account, with the country code.",
        viewModel = viewModel,
        footnote = "Otmgram shows one folder of your chats.\nYou always appear offline.",
        submitLabel = "Next",
        canSubmit = phone.isNotBlank(),
        onSubmit = submit,
    ) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        OtmField(
            value = phone,
            onValueChange = { phone = it },
            placeholder = "+49 151 2345 6789",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("phone"),
        )
    }
}

@Composable
private fun CodeStep(state: AuthState.WaitCode, viewModel: AuthViewModel) {
    var code by rememberSaveable { mutableStateOf("") }
    val submit = { viewModel.submitCode(code) }
    val length = state.length ?: 5
    LaunchedEffect(code) {
        if (state.length != null && code.length == state.length) submit()
    }
    val sentTo = when (state.delivery) {
        CodeDelivery.TelegramApp -> "Telegram sent it to your other device, in the chat with “Telegram”. It’s not an SMS."
        CodeDelivery.Sms -> "We sent it by SMS to ${state.phoneNumber}."
        CodeDelivery.Call -> "You'll get a call to ${state.phoneNumber} that tells the code."
        CodeDelivery.Other -> "We sent it to ${state.phoneNumber}."
    }
    AuthStep(
        heroHeight = 180,
        onBack = viewModel::startOver,
        title = "Enter the code",
        description = sentTo,
        viewModel = viewModel,
        submitLabel = "Next",
        // Codes of a known length are sent as soon as they're complete.
        showSubmit = state.length == null,
        canSubmit = code.isNotBlank(),
        onSubmit = submit,
        bottom = {
            Text(
                buildAnnotatedString {
                    append("${state.phoneNumber} · ")
                    withStyle(SpanStyle(color = Otm.SlateDeep, fontWeight = FontWeight.SemiBold)) {
                        append("Wrong number?")
                    }
                },
                style = OtmType.Secondary,
                color = Otm.InkSoft,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable(enabled = !viewModel.busy, onClick = viewModel::startOver).padding(vertical = 10.dp).testTag("startOver"),
            )
        },
    ) {
        CodeBoxes(code, length, onChange = { code = it.filter(Char::isDigit).take(length) }, onDone = submit)
        if (state.canResend) ResendLine(state, viewModel)
    }
}

/** One box per digit, over an invisible text field that takes the input. */
@Composable
private fun CodeBoxes(code: String, length: Int, onChange: (String) -> Unit, onDone: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        value = code,
        onValueChange = onChange,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.padding(top = 6.dp).focusRequester(focus).testTag("code"),
        decorationBox = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(length) { i ->
                    val active = i == code.length
                    Box(
                        modifier = Modifier
                            .width(if (length > 5) 44.dp else 52.dp)
                            .height(60.dp)
                            .background(Otm.Card, RoundedCornerShape(14.dp))
                            .border(if (active) 2.dp else 1.dp, if (active) Otm.Slate else Otm.Ink.copy(alpha = 0.14f), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(code.getOrNull(i)?.toString().orEmpty(), style = OtmType.Headline.copy(fontSize = 26.sp), color = Otm.Ink)
                    }
                }
            }
        },
    )
}

@Composable
private fun ResendLine(state: AuthState.WaitCode, viewModel: AuthViewModel) {
    var left by remember(state) { mutableIntStateOf(state.resendAfter) }
    LaunchedEffect(state) {
        while (left > 0) {
            delay(1_000)
            left--
        }
    }
    if (left > 0) {
        Text("You can ask for a new code in ${formatDuration(left)}", style = OtmType.Secondary, color = Otm.InkSoft)
    } else {
        Text(
            "Send the code another way",
            style = OtmType.Secondary.copy(fontWeight = FontWeight.SemiBold),
            color = Otm.SlateDeep,
            modifier = Modifier.clickable(enabled = !viewModel.busy, onClick = viewModel::resendCode).padding(vertical = 8.dp).testTag("resend"),
        )
    }
}

@Composable
private fun PasswordStep(state: AuthState.WaitPassword, viewModel: AuthViewModel) {
    var password by rememberSaveable { mutableStateOf("") }
    var visible by rememberSaveable { mutableStateOf(false) }
    val submit = { viewModel.submitPassword(password) }
    AuthStep(
        heroHeight = 180,
        onBack = viewModel::startOver,
        title = "Your password",
        description = "Your account has two-step verification. Enter the password you set in Telegram.",
        viewModel = viewModel,
        submitLabel = "Sign in",
        canSubmit = password.isNotEmpty(),
        onSubmit = submit,
        bottom = {
            Text(
                buildAnnotatedString {
                    append("Use another account · ")
                    withStyle(SpanStyle(color = Otm.SlateDeep, fontWeight = FontWeight.SemiBold)) { append("Start over") }
                },
                style = OtmType.Secondary,
                color = Otm.InkSoft,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable(enabled = !viewModel.busy, onClick = viewModel::startOver).padding(vertical = 10.dp).testTag("startOver"),
            )
        },
    ) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        OtmField(
            value = password,
            onValueChange = { password = it },
            placeholder = "Password",
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("password"),
            trailing = {
                IconBtn(
                    if (visible) OtmIcons.EyeOff else OtmIcons.Eye,
                    if (visible) "Hide password" else "Show password",
                    onClick = { visible = !visible },
                    tint = Otm.InkSoft,
                )
            },
        )
        if (state.hint.isNotEmpty()) Text("Hint: ${state.hint}", style = OtmType.Secondary, color = Otm.InkSoft)
    }
}

@Composable
private fun UnsupportedStep(state: AuthState.Unsupported, viewModel: AuthViewModel) {
    AuthStep(
        heroHeight = 180,
        title = "Can't continue here",
        description = "Telegram asks for ${state.step}, which Otmgram doesn't support. " +
            "Sign in with the official Telegram app once, then try again.",
        viewModel = viewModel,
        submitLabel = "Start over",
        canSubmit = true,
        onSubmit = viewModel::startOver,
    ) {}
}

@Composable
private fun AuthStep(
    heroHeight: Int,
    title: String,
    description: String,
    viewModel: AuthViewModel,
    submitLabel: String,
    canSubmit: Boolean,
    onSubmit: () -> Unit,
    wordmark: Boolean = false,
    onBack: (() -> Unit)? = null,
    footnote: String? = null,
    showSubmit: Boolean = true,
    bottom: @Composable ColumnScope.() -> Unit = {},
    fields: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .paper()
            .imePadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Hero(heroHeight.dp) {
            if (wordmark) Wordmark(Modifier.padding(start = 24.dp, top = 40.dp))
            if (onBack != null) {
                IconBtn(OtmIcons.Back, "Back", onBack, Modifier.padding(start = 8.dp, top = 6.dp), enabled = !viewModel.busy, tag = "back")
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, style = OtmType.Headline, color = Otm.Ink)
            Text(description, style = OtmType.Body, color = Otm.InkSoft)
            fields()
            viewModel.error?.let {
                Text(it, style = OtmType.Secondary, color = Otm.Error, modifier = Modifier.testTag("error"))
            }
            Spacer(Modifier.height(20.dp))
            footnote?.let {
                Text(it, style = OtmType.Secondary, color = Otm.InkSoft, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            if (showSubmit) {
                PrimaryButton(submitLabel, onSubmit, Modifier.testTag("submit"), enabled = canSubmit, busy = viewModel.busy)
            } else if (viewModel.busy) {
                LoadingScreen(Modifier.height(52.dp))
            }
            bottom()
        }
    }
}
