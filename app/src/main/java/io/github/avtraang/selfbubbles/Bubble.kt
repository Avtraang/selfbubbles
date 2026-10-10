package io.github.avtraang.selfbubbles

// The message bubble and everything it is built from: linkified text, HuggingText, LinkCard,
// ReactionChip, the overlapAbove modifier, SaveButton / DownloadGlyph, openExternal, and the
// run/separator/time helpers (sameDay, sameRun, needsDivider, dividerLabel, bubbleTime, fmtTime,
// fmtListTime, DayDivider, looksForeign). Moved verbatim out of MainActivity.kt (SelfBubbles
// split, step C8a).

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import io.github.avtraang.selfbubbles.ui.theme.BubbleShape
import io.github.avtraang.selfbubbles.ui.theme.Dimens
import io.github.avtraang.selfbubbles.ui.theme.MessagesTheme
import io.github.avtraang.selfbubbles.ui.theme.Spacing

// internal for PureHelpersTest
internal fun sameDay(a: Double?, b: Double?): Boolean {
    if (a == null || b == null) return true
    val day = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    return day.format(Date((a * 1000).toLong())) == day.format(Date((b * 1000).toLong()))
}

/** [linkColor]: the bubble's own text color on every bubble; the underline is what marks a link (spec C10). */
// internal for PureHelpersTest
internal fun linkified(text: String, linkColor: Color) = buildAnnotatedString {
    var last = 0
    for (match in URL_REGEX.findAll(text)) {
        append(text.substring(last, match.range.first))
        val url = match.value.trimEnd('.', ',', ')', ';')
        val style = SpanStyle(
            color = linkColor,
            textDecoration = TextDecoration.Underline,
        )
        withLink(LinkAnnotation.Url(url, TextLinkStyles(style = style))) { append(match.value) }
        last = match.range.last + 1
    }
    if (last < text.length) append(text.substring(last))
}

/**
 * The words the bubble of [m] shows in its text bubble, or null for none: the
 * text without Apple's attachment marks (U+FFFC), trimmed, and not when it is
 * only the URL of the message's link card, which the card already shows
 * (Apple's stored URL may carry query params the typed text doesn't,
 * "...?variant=139407", so the two are compared without the query string).
 * What Echo rains across the screen, and what Invisible Ink has to hide.
 */
// internal for ScreenEffectSpecsTest
internal fun bubbleBody(m: Msg): String? {
    val body = m.text?.replace("\uFFFC", "")?.trim()
    if (body.isNullOrBlank()) return null
    val link = m.link ?: return body
    fun bare(u: String?) = u?.trim()?.substringBefore('?')?.trimEnd('/')
    return if (bare(body).equals(bare(link.url), ignoreCase = true)) null else body
}

/**
 * Wrapped Compose Text claims the full offered width; iMessage bubbles hug
 * their widest laid-out line. Pre-measure at the bubble cap, then size the
 * Text to exactly its widest line (+1px so rounding can't force a re-wrap).
 */
@Composable
private fun HuggingText(
    text: AnnotatedString,
    color: Color,
    maxWidth: Dp,
    modifier: Modifier = Modifier,
    // Spec C6: bubble text is bodyLarge (17/22, no letter spacing), stated here rather
    // than inherited, so the bubble reads the same wherever it is composed.
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val fitted = remember(text, style, maxWidth, density) {
        val layout = measurer.measure(
            text, style = style,
            constraints = Constraints(maxWidth = with(density) { maxWidth.roundToPx() }),
        )
        val widest = (0 until layout.lineCount).maxOf { layout.getLineRight(it) }
        with(density) { (kotlin.math.ceil(widest).toInt() + 1).toDp() }
    }
    Text(text, color = color, style = style, modifier = modifier.width(fitted))
}

/** iMessage-style rich link: hero image, title, summary, domain.
 *  [sentColor]/[sentText]: the sent bubble's fill and text (green for SMS/RCS messages so a
 *  URL-only text matches the rest of the thread). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LinkCard(
    link: LinkPreview, mine: Boolean, onLongPress: () -> Unit,
    sentColor: Color, sentText: Color,
    border: BorderStroke? = null,     // spec C16: the MediaFrame hairline
) {
    val ctx = LocalContext.current
    val colors = MessagesTheme.colors
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    // A relay image is a path the relay wrote ("/link_image/…"). An absolute address comes from the
    // sender's link preview; one that names the relay itself is never loaded.
    val img = link.image?.let { if (it.startsWith("/")) BASE + it else it.takeUnless { u -> isRelayUrl(u) } }
    Surface(
        color = if (mine) sentColor else colors.bubbleReceived,
        shape = BubbleShape,
        border = border,
        modifier = Modifier.widthIn(max = Dimens.bubbleMaxWidth).combinedClickable(
            onClick = {
                (link.resolved_url ?: link.url)?.let {
                    runCatching {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)))
                    }
                }
            },
            onLongClick = onLongPress,
        ),
    ) {
        Column {
            // Some links (Apple News) carry no image at all — render text-only
            // rather than reserving an empty grey block. Also collapses if the
            // image URL turns out to be unloadable.
            var imgOk by remember(img) { mutableStateOf(img != null) }
            if (img != null && imgOk) {
                AsyncImage(
                    model = img, contentDescription = null,
                    modifier = Modifier.fillMaxWidth().heightIn(max = Dimens.linkImageMaxHeight),
                    contentScale = ContentScale.Crop,
                    onError = { imgOk = false },
                )
            }
            Column(Modifier.padding(horizontal = Dimens.bubblePadH, vertical = Dimens.bubblePadV)) {
                link.title?.let {
                    Text(
                        it, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (mine) sentText else colors.onBubbleReceived,
                    )
                }
                link.summary?.let {
                    Text(
                        it, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (mine) sentText.copy(alpha = 0.85f) else secondary,
                        modifier = Modifier.padding(top = Spacing.xxs),
                    )
                }
                (link.site ?: (link.resolved_url ?: link.url)?.let { runCatching { Uri.parse(it).host }.getOrNull() })?.let {
                    Text(
                        it.removePrefix("www."), style = MaterialTheme.typography.labelSmall,
                        color = if (mine) sentText.copy(alpha = 0.7f) else secondary,
                        modifier = Modifier.padding(top = Spacing.xs),
                    )
                }
            }
        }
    }
}

/** Consecutive same-sender messages render as one visual run — but a separator
 *  (new day, or more than an hour since the previous message: spec C2/C3) ends
 *  the run, so each chunk gets its own name (top) and avatar (bottom). */
// internal for PureHelpersTest
internal fun sameRun(a: Msg, b: Msg): Boolean =
    a.is_from_me == b.is_from_me && a.sender == b.sender && !needsDivider(a, b)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Bubble(
    m: Msg,
    reactions: List<Reaction>?,
    firstInRun: Boolean = true,
    lastInRun: Boolean = true,
    peek: Dp = Spacing.none,
    onOpenImage: ((String) -> Unit)? = null,
    onOpenPdf: ((PdfTarget) -> Unit)? = null,
    onOpenVideo: ((VideoTarget) -> Unit)? = null,
    audio: AudioController? = null,
    translation: String? = null,
    onTranslate: ((Msg) -> Unit)? = null,
    onDismissTranslation: ((Msg) -> Unit)? = null,
    // A quiet line under the bubble, like "Edited": the conversation says "Unsending…" here while an Undo Send runs.
    note: String? = null,
    onLongPress: (Msg) -> Unit,
) {
    val mine = m.is_from_me
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MessagesTheme.colors
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val sentBg = if (isTextMsg(m)) colors.bubbleSms else colors.bubbleIMessage
    val sentText = if (isTextMsg(m)) colors.onBubbleSms else colors.onBubbleIMessage
    val recvBg = colors.bubbleReceived
    val recvText = colors.onBubbleReceived
    val withAvatar = !mine && m.is_group && m.sender != null
    // The chat's effect engine (EffectPlayer.kt); NoEffects anywhere else a bubble is drawn. The first time this
    // message is on screen (its row composed, which the list does a little ahead of the scroll) its effect plays
    // by itself when the rules allow (PlaybackRules.autoPlays); after that, Replay on the caption line plays it.
    val effects = LocalEffectController.current
    LaunchedEffect(m.guid, m.effect) { effects.playIfUnseen(m) }
    val play = effects.bubblePlay(m.guid)
    val fx = rememberBubbleEffect(play, effects, mine)
    // Whether this message's screen effect is playing over the chat: derived, so only this bubble recomposes when
    // a play starts or ends, not every bubble on screen.
    val screenPlaying by remember(m.guid, effects) { derivedStateOf { effects.screenPlay?.guid == m.guid } }
    // Invisible Ink hides the words (and the reply quote) under a shimmer until a tap; the state is this bubble's own.
    // Not for a message with nothing to hide (attachments only): its shimmer loop would run for no one to see.
    val ink = if (m.effect == MessageEffects.INVISIBLE_INK && InvisibleInkSpec.hidesSomething(m)) rememberInvisibleInk(m.guid, effects.animationsOn) else null
    val wordsHidden = ink?.concealed == true
    Box(Modifier.fillMaxWidth()) {
        // Revealed underneath as the row slides left — iMessage's timestamp peek.
        if (peek > Spacing.none) {
            Text(
                bubbleTime(m.date), style = MaterialTheme.typography.labelMedium, color = secondary,
                fontWeight = FontWeight.Medium,
                // Sits in its own column rather than jammed against the edge:
                // the reveal is wider than this inset, so the label is centred
                // in the space the bubbles vacate.
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = Spacing.lg),
            )
        }
    Row(
        // Spec C3 (reference MessageRow): the whole gap sits above the bubble —
        // 12dp when a run starts, 2dp inside one — so a separator's own padding
        // reads the same above and below it.
        Modifier.fillMaxWidth().offset(x = -peek)
            // Slam's landing shoves the whole row, avatar and all, for 200 ms; the layer exists only while it plays.
            .then(if (fx != null) Modifier.graphicsLayer { translationY = fx.joltPx.floatValue } else Modifier)
            .padding(
            start = Dimens.bubbleGutter, end = Dimens.bubbleGutter,
            top = if (firstInRun) Dimens.bubbleGapBetweenRuns else Dimens.bubbleGapInRun,
        ),
        verticalAlignment = Alignment.Bottom,  // avatar hugs the run bottom
    ) {
        if (withAvatar) {
            if (lastInRun) Avatar(m.sender, Dimens.avatarBubble, m.sender_handle?.let { listOf(it) })
            else Spacer(Modifier.width(Dimens.avatarBubble))
            Spacer(Modifier.width(Spacing.sm))
        }
        Column(
            Modifier.weight(1f),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        ) {
        if (withAvatar && firstInRun) {
            // Spec C15: labelMedium above the first bubble of a run, in a senderNames color, never inside it.
            Text(m.sender ?: "", style = MaterialTheme.typography.labelMedium,
                 color = senderColor(m.sender),
                 modifier = Modifier.padding(start = Dimens.bubblePadH, bottom = Spacing.xxs))
        }
        // The message's own parts (attachments, quote, link card, text) in one column that hugs them, so a bubble
        // effect (Slam, Loud, Gentle: EffectPlayback.kt) scales and turns them together about the bubble's own
        // corner while the sender's name, the reactions and the captions stay still, as on iOS. The layer exists
        // only while an effect plays; the rest of the time this column changes nothing in the layout.
        Column(
            (if (fx != null) Modifier.graphicsLayer {
                scaleX = fx.scale.floatValue
                scaleY = fx.scale.floatValue
                rotationZ = fx.rotation.floatValue
                alpha = fx.alpha.floatValue
                transformOrigin = fx.origin
            } else Modifier)
                // While this message's screen effect plays (the play is set before the overlay's first frame) the
                // column reports where it is, so Spotlight finds the bubble and Love grows out of it, and keeps
                // reporting as the list scrolls. Only then: a positioned callback on every bubble would fire on
                // every scroll of every row.
                .then(if (screenPlaying) Modifier.onGloballyPositioned { effects.reportBounds(m.guid, it.boundsInRoot()) } else Modifier),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        ) {
        // Spec C16 (reference MediaFrame): photos, GIFs, videos and link cards keep the
        // bubble's corners and gain a hairline, so a white picture still has an edge on a
        // white background. The relay does not say which images are transparent stickers,
        // so every image is framed. Attachments and link cards of an Invisible Ink message are
        // simply shown: the ink hides the words, as iOS's does, and the quote with them.
        val mediaFrame = BorderStroke(Dimens.hairline, MaterialTheme.colorScheme.outlineVariant)
        // A 4dp gap between stacked parts (several attachments, then a card or a bubble) but
        // none after the last one, so a reaction chip overlaps the message's real bottom edge.
        var stacked = false
        m.attachments.forEach { a ->
            val full = BASE + a.url
            val mime = a.mime_type ?: ""
            if (stacked) Spacer(Modifier.height(Spacing.xs))
            when {
                mime.startsWith("image/") -> Row(verticalAlignment = Alignment.CenterVertically) {
                    if (mine) SaveButton(full, a.name, mime)
                    AsyncImage(
                        model = full, contentDescription = a.name,
                        modifier = Modifier.widthIn(max = Dimens.mediaMaxWidth)
                            .clip(BubbleShape)
                            .border(mediaFrame, BubbleShape)
                            .combinedClickable(
                                // In-app viewer: Coil already carries the auth
                                // token, and handing off to an external app was
                                // unreliable (blank pages, no HEIC handler).
                                onClick = {
                                    if (onOpenImage != null) onOpenImage(full)
                                    else openExternal(ctx, full, mime, a.name, scope)
                                },
                                onLongClick = { onLongPress(m) },
                            ),
                    )
                    if (!mine) SaveButton(full, a.name, mime)
                }
                mime.startsWith("video/") -> Row(verticalAlignment = Alignment.CenterVertically) {
                    if (mine) SaveButton(full, a.name, mime)
                    Box(
                        modifier = Modifier.widthIn(max = Dimens.mediaMaxWidth)
                            .clip(BubbleShape)
                            .border(mediaFrame, BubbleShape)
                            .combinedClickable(
                                // Built-in player: streams with the auth header, which another app can't send.
                                onClick = {
                                    if (onOpenVideo != null && isRelayUrl(full)) onOpenVideo(VideoTarget(full, a.name, videoMimeFor(mime, a.name)))
                                    else openExternal(ctx, full, mime, a.name, scope)
                                },
                                onLongClick = { onLongPress(m) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncImage(
                            model = "$BASE/thumbnail/${a.guid}",
                            contentDescription = a.name,
                        )
                        PlayOverlay()
                    }
                    if (!mine) SaveButton(full, a.name, mime)
                }
                // Voice messages play inside the bubble through the chat's shared player
                // (relay audio only: the SMS side and off-relay URLs keep the file chip).
                audio != null && !isTextMsg(m) && isAudioAttachment(mime, a.name) && isRelayUrl(full) -> AudioBubble(
                    guid = a.guid, url = full, name = a.name, controller = audio,
                    bg = if (mine) sentBg else recvBg, fg = if (mine) sentText else recvText,
                    onLongPress = { onLongPress(m) },
                )
                // Contact cards (vCard) show as a card with "Add to contacts"; relay URLs only
                // (SMS/RCS attachments are relay paths too, so they get the same card).
                isContactCardAttachment(mime, a.name) && isRelayUrl(full) -> ContactCardBubble(
                    guid = a.guid, url = full, name = a.name,
                    bg = if (mine) sentBg else recvBg, fg = if (mine) sentText else recvText,
                    onLongPress = { onLongPress(m) },
                    onOpenExternally = { openExternal(ctx, full, mime, a.name, scope) },
                )
                else -> Surface(
                    color = if (mine) sentBg else recvBg,
                    shape = BubbleShape,
                    modifier = Modifier.widthIn(max = Dimens.bubbleMaxWidth).combinedClickable(
                        onClick = {
                            // PDFs open in the built-in viewer; the system chooser often offers none.
                            if (onOpenPdf != null && isPdfAttachment(mime, a.name) && isRelayUrl(full)) onOpenPdf(PdfTarget(full, a.name))
                            else if (onOpenVideo != null && isVideoAttachment(mime, a.name) && isRelayUrl(full)) onOpenVideo(VideoTarget(full, a.name, videoMimeFor(mime, a.name)))
                            else openExternal(ctx, full, mime, a.name, scope)
                        },
                        onLongClick = { onLongPress(m) },
                    ),
                ) {
                    Text(
                        (if (mime.startsWith("video/")) "\u25B6  " else "\uD83D\uDCC4  ") + (a.name ?: "attachment"),
                        style = MaterialTheme.typography.bodyLarge,   // spec C6: same type as a text bubble
                        modifier = Modifier.padding(horizontal = Dimens.bubblePadH, vertical = Dimens.bubblePadV),
                        color = if (mine) sentText else recvText,
                    )
                }
            }
            stacked = true
        }
        m.reply_to?.let { q ->
            if (stacked) Spacer(Modifier.height(Spacing.xs))
            stacked = false    // the quote carries its own 4dp gap below
            // Spec C13 (reference ReplyQuote): a 2dp outline bar beside up to two lines of the
            // quoted text in bodySmall secondary, 4dp above the reply bubble on the same side.
            // The app's sender line stays above the text, in the same secondary color.
            Row(
                Modifier.widthIn(max = Dimens.bubbleMaxWidth).height(IntrinsicSize.Min).padding(bottom = Spacing.xs)
                    // Invisible Ink hides the quoted words too, under the same shimmer in the quote's own grey.
                    // The quote takes the reveal tap as the text bubble does: a reply with no words of its own
                    // (a photo, a bare link) has no text bubble, and its quote would otherwise stay hidden for good.
                    // While hidden, TalkBack gets the same one line in the quote's place.
                    .then(if (ink != null) Modifier.invisibleInk(ink, secondary)
                              .combinedClickable(onClick = { ink.reveal() }, onLongClick = { onLongPress(m) }) else Modifier)
                    .then(if (wordsHidden) Modifier.clearAndSetSemantics { contentDescription = INVISIBLE_INK_DESCRIPTION } else Modifier),
            ) {
                Box(Modifier.width(Spacing.xxs).fillMaxHeight().background(MaterialTheme.colorScheme.outline, CircleShape))
                Spacer(Modifier.width(Spacing.sm))
                Column {
                    if (q.sender.isNotBlank()) {
                        Text(q.sender, style = MaterialTheme.typography.labelSmall, color = secondary)
                    }
                    Text(
                        q.text, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, color = secondary,
                    )
                }
            }
        }
        val link = m.link
        val showLink = link != null && (link.title != null || link.image != null)
        if (showLink && link != null) {
            if (stacked) Spacer(Modifier.height(Spacing.xs))
            LinkCard(link, mine, onLongPress = { onLongPress(m) },
                     sentColor = sentBg, sentText = sentText, border = mediaFrame)
            stacked = true
        }
        // The words, unless they are only the link card's own URL (bubbleBody).
        val body = bubbleBody(m)
        if (body != null) {
            // The text sits flush under a link card, as before; after media it gets the 4dp gap.
            if (stacked && !showLink) Spacer(Modifier.height(Spacing.xs))
            Surface(
                color = if (mine) sentBg else recvBg,
                shape = BubbleShape,
                // iMessage-style cap: bubbles never span the full screen width.
                modifier = Modifier.widthIn(max = Dimens.bubbleMaxWidth)
                    // A tap reveals Invisible Ink (and does nothing on any other bubble); the long press keeps its menu.
                    .combinedClickable(onClick = { ink?.reveal() }, onLongClick = { onLongPress(m) })
                    // While the words are hidden TalkBack gets one line in their place; the click action sits outside.
                    .then(if (wordsHidden) Modifier.clearAndSetSemantics { contentDescription = INVISIBLE_INK_DESCRIPTION } else Modifier),
            ) {
                HuggingText(
                    // Spec C10: a link keeps the bubble's own text color; the underline marks it.
                    // (#007AFF on the gray received bubble was 3.4:1 in dark mode.)
                    // No link spans while the words are hidden: one would take the tap meant to reveal them.
                    if (wordsHidden) AnnotatedString(body)
                    else linkified(body, linkColor = if (mine) sentText else MessagesTheme.colors.onBubbleReceived),
                    color = if (mine) sentText else recvText,
                    maxWidth = Dimens.bubbleMaxWidth - Dimens.bubblePadH * 2,  // bubble cap minus its side padding
                    // The shimmer, in the bubble's text colour, covers the whole interior: drawn outside the padding.
                    modifier = (if (ink != null) Modifier.invisibleInk(ink, if (mine) sentText else recvText) else Modifier)
                        .padding(horizontal = Dimens.bubblePadH, vertical = Dimens.bubblePadV),
                )
            }
        }
        }   // the message's own parts
        if (!reactions.isNullOrEmpty()) {
            // Spec C12 (reference ReactionChip): the chips ride 8dp up over the bottom edge of
            // whatever was drawn last (bubble, link card or photo) and sit 12dp in from its
            // outer edge, so a reacted message grows by 18dp rather than a detached 32dp.
            Row(
                Modifier.padding(horizontal = Dimens.bubblePadH).overlapAbove(Dimens.reactionOverlap),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                reactions.forEach { r -> ReactionChip(r.emoji, r.count, mine = r.mine) }
            }
        }
        // One-tap copy for verification codes, and translate-on-demand for
        // messages in another script. Both render as quiet chips under the text.
        // Not while Invisible Ink hides the words: "Copy 123456", Translate or a translation would give them away.
        val otp = remember(m.guid) { Otp.find(m.text) }
        val foreign = remember(m.guid) { looksForeign(m.text) }
        if (!wordsHidden && (otp != null || (foreign && translation == null && onTranslate != null))) {
            Row(Modifier.padding(top = Spacing.xxs)) {
                if (otp != null) {
                    ActionChip("Copy $otp") {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("code", otp))
                        Toast.makeText(ctx, "Copied $otp", Toast.LENGTH_SHORT).show()
                    }
                }
                if (foreign && translation == null && onTranslate != null) {
                    if (otp != null) Spacer(Modifier.width(Spacing.sm))
                    ActionChip("Translate") { onTranslate(m) }
                }
            }
        }
        translation?.takeIf { it.isNotBlank() && !wordsHidden }?.let { tr ->
            Surface(
                color = recvBg.copy(alpha = 0.55f),
                shape = RoundedCornerShape(Dimens.cardRadius),
                modifier = Modifier.padding(top = Spacing.xs).widthIn(max = Dimens.bubbleMaxWidth)
                    .clip(RoundedCornerShape(Dimens.cardRadius))
                    .clickable(enabled = tr != "…") { onDismissTranslation?.invoke(m) },
            ) {
                Column(Modifier.padding(horizontal = Dimens.bubblePadH, vertical = Dimens.bubblePadV)) {
                    Text(
                        if (tr == "…") "Translating…" else tr,
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        color = recvText.copy(alpha = 0.9f),
                    )
                    if (tr != "…") {
                        Text(
                            "Tap to hide translation",
                            style = MaterialTheme.typography.labelSmall,
                            color = secondary.copy(alpha = 0.8f),
                            modifier = Modifier.padding(top = Spacing.xs),
                        )
                    }
                }
            }
        }
        // Not under one of the owner's own messages with nothing left to show: the Mac stamps an
        // edit date on an unsent message too, and a lone "Edited" where the bubble was is not what
        // Undo Send should leave behind (showsEditedCaption, EditUnsend.kt).
        if (showsEditedCaption(m)) {
            // labelSmall's tight 11/14 keeps the caption from floating halfway
            // between messages on line-box slack.
            Text("Edited", style = MaterialTheme.typography.labelSmall, color = secondary,
                 modifier = Modifier.padding(horizontal = Spacing.xs))
        }
        // A message sent with an effect says so in iOS's words ("Sent with Confetti"), under a received
        // bubble as under a sent one (effectCaptionFor, MessageEffects.kt), with Replay beside it where the
        // rules allow (a known effect, not Invisible Ink, animations on, nothing playing: PlaybackRules.showsReplay).
        // Replay plays it through the chat's controller: a bubble effect on the bubble, a screen effect over the chat.
        effectCaptionFor(m)?.let { caption ->
            // Replay keeps its place in the line while the effect plays (the word drawn clear, taking no tap and
            // saying nothing to TalkBack) rather than leaving it: its padding makes the line taller than the caption
            // alone, and in this bottom-anchored list a line that lost and regained that height would move every
            // older row for the length of the play, and grow a new message's row when its auto-play ended.
            val replayable = PlaybackRules.showsReplay(m.effect, effects.animationsOn, playing = false)
            val replay = PlaybackRules.showsReplay(m.effect, effects.animationsOn, playing = play != null)
            Row(Modifier.padding(horizontal = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                Text(if (replay) "$caption ·" else caption, style = MaterialTheme.typography.labelSmall, color = secondary)
                if (replayable) m.effect?.let { effect ->
                    // A caption-sized text button, as iOS puts it. The padding inside the click widens the target a
                    // little; Material's 48dp minimum would turn the caption line into a row of buttons.
                    Text("Replay", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                         modifier = Modifier.alpha(if (replay) 1f else 0f)
                             .then(if (replay) Modifier.clickable(role = Role.Button) { effects.play(m, effect) }
                                   else Modifier.clearAndSetSemantics {})
                             .padding(horizontal = Spacing.xs, vertical = Spacing.xxs))
                }
            }
        }
        if (note != null) {
            // TalkBack reads the note when it appears (as WarningStrip's text): nothing else says an Undo Send has started.
            Text(note, style = MaterialTheme.typography.labelSmall, color = secondary,
                 modifier = Modifier.padding(horizontal = Spacing.xs).semantics { liveRegion = LiveRegionMode.Polite })
        }
        }
    }
    }
}

/** Moves a composable up by [overlap] and gives that height back to the layout, so the next item closes the gap. */
private fun Modifier.overlapAbove(overlap: Dp) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val shift = overlap.roundToPx()
    layout(placeable.width, (placeable.height - shift).coerceAtLeast(0)) { placeable.place(0, -shift) }
}

/**
 * One tapback on a bubble (spec C12). Display only, as today: a plain Row rather than a Surface,
 * so a long press on the 8dp of bubble it covers still reaches the bubble. 18dp label line plus
 * 2 x 4dp padding = 26dp tall; the 2dp ring in the background color cuts it out of the bubble.
 */
@Composable
private fun ReactionChip(emoji: String, count: Int, mine: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    // One gray fill for every chip: a blue "mine" fill vanished against a sent blue bubble (review note);
    // "yours" is spoken instead of shown by color alone.
    val content = scheme.onSurface
    Row(
        modifier
            // One spoken item instead of a bare emoji and a number.
            .clearAndSetSemantics {
                contentDescription = (if (count > 1) "$count reactions: $emoji" else "Reaction: $emoji") + if (mine) ", yours" else ""
            }
            .background(scheme.surfaceContainerHighest, CircleShape)
            .border(Spacing.xxs, scheme.background, CircleShape)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji, style = MaterialTheme.typography.labelMedium, color = content)
        if (count > 1) {
            Spacer(Modifier.width(Spacing.xs))
            Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = content)
        }
    }
}

/**
 * Opens an attachment in another app. Relay URLs get downloaded first and handed
 * over as a content:// URI — an external app can't send our auth header, and a
 * query-param token gets stripped by some viewers (Google Photos does). Anything
 * not on the relay is opened directly.
 */
internal fun openExternal(
    ctx: android.content.Context,
    url: String,
    mime: String,
    name: String? = null,
    scope: kotlinx.coroutines.CoroutineScope? = null,
) {
    if (isRelayUrl(url) && scope != null) {
        scope.launch {
            val message = when (Downloads.openExternallyOutcome(ctx, url, name, mime)) {
                Downloads.OpenOutcome.OK -> null
                Downloads.OpenOutcome.MISSING -> ATTACHMENT_MISSING_MESSAGE
                Downloads.OpenOutcome.FAILED -> "Couldn't open attachment"
            }
            if (message != null) Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
        }
        return
    }
    val i = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(url), mime.ifBlank { "*/*" })
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { ctx.startActivity(i) }
}

// internal for PureHelpersTest
internal fun fmtTime(unix: Double): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date((unix * 1000).toLong()))

/** True when most letters are outside the Latin script — Persian, Arabic,
 *  Hebrew, CJK, Cyrillic. Cheap, offline, and exactly right for family group
 *  chats; Latin-script languages just don't get the auto-button. */
fun looksForeign(text: String?): Boolean {
    if (text.isNullOrBlank()) return false
    var letters = 0
    var nonLatin = 0
    for (c in text) {
        if (!c.isLetter()) continue
        letters++
        val b = Character.UnicodeBlock.of(c)
        if (b != Character.UnicodeBlock.BASIC_LATIN &&
            b != Character.UnicodeBlock.LATIN_1_SUPPLEMENT &&
            b != Character.UnicodeBlock.LATIN_EXTENDED_A &&
            b != Character.UnicodeBlock.LATIN_EXTENDED_B
        ) nonLatin++
    }
    return letters >= 2 && nonLatin.toFloat() / letters > 0.4f
}

/** Arrow-into-tray download icon. Drawn rather than imported — Material's core
 *  icon set has no download glyph, and pulling in material-icons-extended for
 *  one shape isn't worth the build weight. */
@Composable
private fun DownloadGlyph(tint: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val s = w * 0.10f
        drawLine(tint, Offset(w / 2, h * 0.16f), Offset(w / 2, h * 0.60f), s, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.28f, h * 0.42f), Offset(w / 2, h * 0.63f), s, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.72f, h * 0.42f), Offset(w / 2, h * 0.63f), s, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.22f, h * 0.84f), Offset(w * 0.78f, h * 0.84f), s, StrokeCap.Round)
    }
}

/** Floating save-to-gallery button, Beeper-style, on image and video bubbles. */
@Composable
private fun SaveButton(url: String, name: String?, mime: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val glyph = scheme.onPrimaryContainer   // white on both the blue and the green fill
    Box(
        // Solid, high-contrast, and bigger — a translucent 32dp circle vanished
        // against busy screenshots. The ring is white (onScrim) in both themes:
        // the button sits beside the media on the list background, so the
        // spec's background-color ring rule (for marks over avatars and
        // bubbles) would make it black-on-black in dark mode.
        modifier.padding(Spacing.sm).size(Dimens.saveButton).clip(CircleShape)
            .background(if (done) MessagesTheme.colors.bubbleSms else scheme.primaryContainer)
            .border(Spacing.xxs, MessagesTheme.colors.onScrim.copy(alpha = 0.9f), CircleShape)
            .clickable(enabled = !busy && !done) {
                busy = true
                scope.launch {
                    val ok = Downloads.save(ctx, url, name, mime)
                    busy = false
                    done = ok
                    Downloads.toast(ctx, ok)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            busy -> CircularProgressIndicator(
                Modifier.size(Dimens.iconSmall), strokeWidth = Dimens.progressStroke, color = glyph,
            )
            done -> Icon(
                Icons.Filled.Check, contentDescription = "Saved",
                tint = glyph, modifier = Modifier.size(Dimens.iconSmall),
            )
            else -> DownloadGlyph(glyph, Dimens.iconSmall)
        }
    }
}

/** Day wording for a separator: "Today", "Yesterday", "Sun, Jul 12". DayDivider
 *  appends the time; the left-drag peek still shows each message's own time. */
// internal for PureHelpersTest
internal fun dividerLabel(unix: Double): String {
    val d = Date((unix * 1000).toLong())
    val day = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    val today = Calendar.getInstance()
    val yest = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    return when (day.format(d)) {
        day.format(today.time) -> "Today"
        day.format(yest.time) -> "Yesterday"
        else -> SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(d)
    }
}

/** Seconds of silence after which a separator (with its time) is inserted: spec C2. */
private const val SEPARATOR_GAP_SECONDS = 60.0 * 60.0

/** A separator when the day changes or more than an hour passed since the
 *  previous message (spec C2) — the same boundary that ends a run (C3). */
// internal for PureHelpersTest
internal fun needsDivider(prev: Msg?, m: Msg): Boolean {
    if (m.date == null) return false
    if (prev?.date == null) return true
    return !sameDay(prev.date, m.date) || abs(m.date - prev.date) > SEPARATOR_GAP_SECONDS
}

// internal for PureHelpersTest
internal fun bubbleTime(unix: Double?): String =
    unix?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date((it * 1000).toLong())) }
        ?: ""

/** Spec C2 (reference DateSeparatorRow): "Sun, Sep 27 · 09:12" — the day wording
 *  plus the 24-hour time of the first message after the gap. */
@Composable
internal fun DayDivider(unix: Double) {
    Text(
        dividerLabel(unix) + " · " + bubbleTime(unix),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.lg, bottom = Spacing.xs),
    )
}

/** Thread-list timestamp, iMessage-style: time-of-day today, short date otherwise. */
internal fun fmtListTime(unix: Double): String {
    val d = Date((unix * 1000).toLong())
    val day = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    val pattern = if (day.format(d) == day.format(Date())) "HH:mm" else "MMM d"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(d)
}
