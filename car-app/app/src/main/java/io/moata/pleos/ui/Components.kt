package io.moata.pleos.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties


@Composable
fun BigButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = 104.dp),
        shape = RoundedCornerShape(28.dp),
        contentPadding = PaddingValues(horizontal = 40.dp, vertical = 16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (danger) CoralText else MoaYellow, contentColor = Ink,
            disabledContainerColor = SurfaceHi, disabledContentColor = Muted,
        ),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}


@Composable
fun HeroButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 136.dp),
        shape = RoundedCornerShape(36.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MoaYellow, contentColor = Ink, disabledContainerColor = SurfaceHi, disabledContentColor = Muted),
    ) { Text(text, style = MaterialTheme.typography.headlineMedium) }
}


@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, content: Color = Paper, outline: Color = Muted, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = Space.touch),
        shape = RoundedCornerShape(24.dp), border = BorderStroke(2.dp, if (enabled) outline else SurfaceHi),
        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 12.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = content, disabledContentColor = Muted),
    ) { Text(text, style = MaterialTheme.typography.labelMedium) }
}


@Composable
fun TextAction(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, leading: ImageVector? = null, trailing: ImageVector? = null, onClick: () -> Unit) {
    val tint = if (enabled) TealText else Muted
    Row(
        modifier.heightIn(min = Space.touch).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        leading?.let { Icon(it, contentDescription = null, tint = tint, modifier = Modifier.size(32.dp)) }
        Text(text, style = MaterialTheme.typography.labelMedium, color = tint)
        trailing?.let { Icon(it, contentDescription = null, tint = tint, modifier = Modifier.size(32.dp)) }
    }
}


@Composable
fun ExpandAction(label: String, expanded: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) =
    TextAction(label, modifier.semantics { stateDescription = if (expanded) "펼쳐짐" else "접힘" }, trailing = if (expanded) MoaIcons.ChevronUp else MoaIcons.ChevronDown, onClick = onToggle)

fun Modifier.asHeading(): Modifier = semantics { heading() }


@Composable
fun Pill(text: String, fg: Color = Ink, bg: Color = MoaYellow, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 22.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = fg, modifier = Modifier.size(28.dp)) }
        Text(text, color = fg, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun Section(title: String? = null, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().background(Surface1, RoundedCornerShape(32.dp)).padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, color = MoaYellow, modifier = Modifier.asHeading())
        content()
    }
}

@Composable
fun KeyValue(k: String, v: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(k, color = Muted, style = MaterialTheme.typography.bodyMedium)
        Text(v, style = MaterialTheme.typography.bodyMedium.copy(color = Paper))
    }
}

@Composable
fun TextInput(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label, style = MaterialTheme.typography.bodySmall) }, singleLine = true, modifier = modifier, textStyle = MaterialTheme.typography.bodyLarge)
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> Choice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        options.forEach { (v, label) ->
            val on = v == selected
            Row(
                Modifier
                    .heightIn(min = Space.touch)
                    .background(if (on) MoaYellow else SurfaceHi, RoundedCornerShape(24.dp))
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(v) })
                    .padding(horizontal = 28.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                if (on) Icon(MoaIcons.Check, contentDescription = null, tint = Ink, modifier = Modifier.size(32.dp))
                Text(label, color = if (on) Ink else Paper, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
}


fun grouped(raw: String): String {
    if (raw.isEmpty()) return "0"
    val (whole, frac) = raw.substringBefore('.') to raw.substringAfter('.', "").takeIf { raw.contains('.') }
    val w = whole.ifEmpty { "0" }.reversed().chunked(3).joinToString(",").reversed()
    return if (frac != null) "$w.$frac" else w
}


const val BACKSPACE = "⌫"

fun pressKey(value: String, key: String, decimal: Boolean, maxLen: Int = 9): String = when (key) {
    BACKSPACE -> value.dropLast(1)
    "." -> if (!decimal || value.contains('.')) value else if (value.isEmpty()) "0." else "$value."
    else -> if (value.length >= maxLen) value else if (value == "0") key else value + key
}


@Composable
fun NumberPad(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, decimal: Boolean = false, maxLen: Int = 9) {
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(if (decimal) "." else "", "0", BACKSPACE))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                row.forEach { k ->
                    if (k.isEmpty()) Box(Modifier.weight(1f).heightIn(min = 104.dp))
                    else Box(
                        Modifier.weight(1f).heightIn(min = 104.dp).background(SurfaceHi, RoundedCornerShape(24.dp))
                            .clickable(role = Role.Button) { onChange(pressKey(value, k, decimal, maxLen)) }
                            .then(if (k == BACKSPACE) Modifier.semantics { contentDescription = "지우기" } else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (k == BACKSPACE) Icon(MoaIcons.Backspace, contentDescription = null, tint = Paper, modifier = Modifier.size(52.dp))
                        else Text(k, style = MaterialTheme.typography.headlineMedium, color = Paper)
                    }
                }
            }
        }
    }
}


@Composable
fun ValueCard(label: String, value: String, unit: String, selected: Boolean, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().background(Surface1, RoundedCornerShape(32.dp))
            .then(if (selected) Modifier.border(3.dp, MoaYellow, RoundedCornerShape(32.dp)) else Modifier)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick).semantics { this.selected = selected } else Modifier)
            .padding(Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (selected) MoaYellow else Muted)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(if (value.isEmpty()) "—" else grouped(value), style = MaterialTheme.typography.displayLarge.copy(fontSize = 84.sp), color = if (value.isEmpty()) Muted else Paper)
            Text(unit, style = MaterialTheme.typography.titleLarge, color = Muted, modifier = Modifier.padding(bottom = 14.dp))
        }
    }
}


@Composable
fun BigDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.widthIn(max = 1040.dp).fillMaxWidth(0.82f).background(Surface1, RoundedCornerShape(40.dp)).verticalScroll(rememberScrollState()).padding(Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) { content() }
    }
}

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, cancel: String, onConfirm: () -> Unit, onCancel: () -> Unit, danger: Boolean = false) {
    BigDialog(onCancel) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = Paper, modifier = Modifier.asHeading())
        Text(body, style = MaterialTheme.typography.bodyLarge, color = Muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
            GhostButton(cancel, Modifier.weight(1f).heightIn(min = 104.dp), onClick = onCancel)
            BigButton(confirm, Modifier.weight(1f), danger = danger, onClick = onConfirm)
        }
    }
}

@Composable
fun InfoDialog(title: String, lines: List<String>, onClose: () -> Unit) {
    BigDialog(onClose) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = Paper, modifier = Modifier.asHeading())
        lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = Muted, textAlign = TextAlign.Start) }
        BigButton("확인", Modifier.fillMaxWidth(), onClick = onClose)
    }
}

