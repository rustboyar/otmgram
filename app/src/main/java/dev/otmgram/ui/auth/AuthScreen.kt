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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import dev.otmgram.R
import dev.otmgram.data.AuthState
import dev.otmgram.data.CodeDelivery
import dev.otmgram.ui.format.formatDuration
import dev.otmgram.ui.theme.Hero
import dev.otmgram.ui.theme.IconBtn
import dev.otmgram.ui.theme.LoadingScreen
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
    // The steps get what they show and do, not the view model.
    val progress = Progress(viewModel.busy, viewModel.error)
    when (state) {
        AuthState.Loading, AuthState.Ready -> LoadingScreen()
        AuthState.WaitPhoneNumber -> PhoneNumberStep(progress, onSubmit = viewModel::submitPhoneNumber)
        is AuthState.WaitCode -> CodeStep(state, progress, onSubmit = viewModel::submitCode, onResend = viewModel::resendCode, onStartOver = viewModel::startOver)
        is AuthState.WaitPassword -> PasswordStep(state, progress, onSubmit = viewModel::submitPassword, onStartOver = viewModel::startOver)
        is AuthState.Unsupported -> UnsupportedStep(state, progress, onStartOver = viewModel::startOver)
    }
}

/** A request on its way ([busy]), or why the last one failed. */
private data class Progress(val busy: Boolean, val error: String?)

@Composable
private fun PhoneNumberStep(progress: Progress, onSubmit: (String) -> Unit) {
    var phone by rememberSaveable { mutableStateOf("") }
    val submit = { onSubmit(phone) }
    AuthStep(
        heroHeight = 300,
        wordmark = true,
        title = stringResource(R.string.sign_in),
        description = stringResource(R.string.phone_description),
        progress = progress,
        footnote = stringResource(R.string.phone_footnote),
        submitLabel = stringResource(R.string.next),
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
private fun CodeStep(state: AuthState.WaitCode, progress: Progress, onSubmit: (String) -> Unit, onResend: () -> Unit, onStartOver: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    val submit = { onSubmit(code) }
    val length = state.length ?: 5
    // Sent once when complete; turning the phone doesn't send it again.
    var sent by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(code) {
        if (state.length != null && code.length == state.length && code != sent) {
            sent = code
            submit()
        }
    }
    val sentTo = when (state.delivery) {
        CodeDelivery.TelegramApp -> stringResource(R.string.code_sent_app)
        CodeDelivery.Sms -> stringResource(R.string.code_sent_sms, state.phoneNumber)
        CodeDelivery.Call -> stringResource(R.string.code_sent_call, state.phoneNumber)
        CodeDelivery.Other -> stringResource(R.string.code_sent_other, state.phoneNumber)
    }
    AuthStep(
        heroHeight = 180,
        onBack = onStartOver,
        title = stringResource(R.string.code_title),
        description = sentTo,
        progress = progress,
        submitLabel = stringResource(R.string.next),
        // Codes of a known length are sent as soon as they're complete.
        showSubmit = state.length == null,
        canSubmit = code.isNotBlank(),
        onSubmit = submit,
        bottom = {
            Text(
                buildAnnotatedString {
                    append("${state.phoneNumber} · ")
                    withStyle(SpanStyle(color = Otm.SlateDeep, fontWeight = FontWeight.SemiBold)) {
                        append(stringResource(R.string.wrong_number))
                    }
                },
                style = OtmType.Secondary,
                color = Otm.InkSoft,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !progress.busy, onClick = onStartOver, role = Role.Button).padding(vertical = 10.dp).testTag("startOver"),
            )
        },
    ) {
        CodeBoxes(code, length, onChange = { code = it.filter(Char::isDigit).take(length) }, onDone = submit)
        if (state.canResend) ResendLine(state, progress.busy, onResend)
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
private fun ResendLine(state: AuthState.WaitCode, busy: Boolean, onResend: () -> Unit) {
    var left by remember(state) { mutableIntStateOf(state.resendAfter) }
    LaunchedEffect(state) {
        while (left > 0) {
            delay(1_000)
            left--
        }
    }
    if (left > 0) {
        Text(stringResource(R.string.code_resend_in, formatDuration(left)), style = OtmType.Secondary, color = Otm.InkSoft)
    } else {
        Text(
            stringResource(R.string.code_resend),
            style = OtmType.Secondary.copy(fontWeight = FontWeight.SemiBold),
            color = Otm.SlateDeep,
            modifier = Modifier.heightIn(min = 48.dp).clickable(enabled = !busy, onClick = onResend, role = Role.Button).padding(vertical = 8.dp).testTag("resend"),
        )
    }
}

@Composable
private fun PasswordStep(state: AuthState.WaitPassword, progress: Progress, onSubmit: (String) -> Unit, onStartOver: () -> Unit) {
    var password by rememberSaveable { mutableStateOf("") }
    var visible by rememberSaveable { mutableStateOf(false) }
    val submit = { onSubmit(password) }
    AuthStep(
        heroHeight = 180,
        onBack = onStartOver,
        title = stringResource(R.string.password_title),
        description = stringResource(R.string.password_description),
        progress = progress,
        submitLabel = stringResource(R.string.sign_in),
        canSubmit = password.isNotEmpty(),
        onSubmit = submit,
        bottom = {
            Text(
                buildAnnotatedString {
                    append(stringResource(R.string.another_account) + " · ")
                    withStyle(SpanStyle(color = Otm.SlateDeep, fontWeight = FontWeight.SemiBold)) { append(stringResource(R.string.start_over)) }
                },
                style = OtmType.Secondary,
                color = Otm.InkSoft,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !progress.busy, onClick = onStartOver, role = Role.Button).padding(vertical = 10.dp).testTag("startOver"),
            )
        },
    ) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        OtmField(
            value = password,
            onValueChange = { password = it },
            placeholder = stringResource(R.string.password),
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("password"),
            trailing = {
                IconBtn(
                    if (visible) OtmIcons.EyeOff else OtmIcons.Eye,
                    if (visible) stringResource(R.string.password_hide) else stringResource(R.string.password_show),
                    onClick = { visible = !visible },
                    tint = Otm.InkSoft,
                )
            },
        )
        if (state.hint.isNotEmpty()) Text(stringResource(R.string.password_hint, state.hint), style = OtmType.Secondary, color = Otm.InkSoft)
    }
}

@Composable
private fun UnsupportedStep(state: AuthState.Unsupported, progress: Progress, onStartOver: () -> Unit) {
    AuthStep(
        heroHeight = 180,
        title = stringResource(R.string.unsupported_title),
        description = stringResource(R.string.unsupported_description, stringResource(state.step)),
        progress = progress,
        submitLabel = stringResource(R.string.start_over),
        canSubmit = true,
        onSubmit = onStartOver,
    ) {}
}

@Composable
private fun AuthStep(
    heroHeight: Int,
    title: String,
    description: String,
    progress: Progress,
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
                IconBtn(OtmIcons.Back, stringResource(R.string.back), onBack, Modifier.padding(start = 8.dp, top = 6.dp), enabled = !progress.busy, tag = "back")
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, style = OtmType.Headline, color = Otm.Ink)
            Text(description, style = OtmType.Body, color = Otm.InkSoft)
            fields()
            progress.error?.let {
                Text(it, style = OtmType.Secondary, color = Otm.Error, modifier = Modifier.testTag("error"))
            }
            Spacer(Modifier.height(20.dp))
            footnote?.let {
                Text(it, style = OtmType.Secondary, color = Otm.InkSoft, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            if (showSubmit) {
                PrimaryButton(submitLabel, onSubmit, Modifier.testTag("submit"), enabled = canSubmit, busy = progress.busy)
            } else if (progress.busy) {
                LoadingScreen(Modifier.height(52.dp))
            }
            bottom()
        }
    }
}
