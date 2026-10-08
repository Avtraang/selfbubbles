package io.github.avtraang.selfbubbles.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Spacing steps for gaps and paddings. */
object Spacing {
    val none = 0.dp                 // "no offset" for a Dp parameter, such as the timestamp peek at rest
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
}

/** Named component sizes. A gap or padding is a Spacing step or one of these; no other dp values in the app. */
object Dimens {
    val touchTarget = 48.dp
    val iconSmall = 20.dp
    val hairline = 0.5.dp
    val topBarMinHeight = 64.dp

    val screenGutter = 16.dp        // thread list and headers: content to screen edge
    val bubbleGutter = 12.dp        // bubble to screen edge

    val avatarRow = 48.dp           // avatar sizes are passed in by the caller
    val avatarPinned = 64.dp
    val avatarSmall = 40.dp         // search results
    const val avatarInitialsFraction = 0.38f   // initials font size as a share of the avatar
    const val groupPhotoFirstFraction = 0.74f  // stacked group photos: the large one at the top start
    const val groupPhotoSecondFraction = 0.52f // the small one at the bottom end, ringed in the background color
    val unreadDot = 10.dp
    val unreadDotPinned = 14.dp     // plus a 2dp ring in the background color
    val networkDot = 10.dp          // green dot on text threads, plus the same ring
    val unreadGutter = 26.dp        // space left of the row avatar that holds the dot
    val threadRowMinHeight = 72.dp
    val searchRowMinHeight = 56.dp
    val listBottomPad = 88.dp       // 56dp button + 16dp margin + 16dp air

    val bubbleRadius = 18.dp
    val bubblePadH = 12.dp
    val bubblePadV = 7.dp           // 22sp line + 2 x 7dp = a 36dp pill
    const val bubbleMaxWidthFraction = 0.83f   // of the list inside its 12dp gutters = 78% of the screen, as today
    val bubbleGapInRun = 2.dp
    val bubbleGapBetweenRuns = 12.dp
    val reactionOverlap = 8.dp      // how far a reaction chip rides up over its bubble

    val fieldHeight = 40.dp         // search field and composer field (minimum)
    val fieldRadius = 20.dp
    val fieldBorder = 1.dp
    val sendButton = 36.dp          // drawn size; touch target stays 48
    val composerEndPad = 6.dp       // puts the send circle's edge on the bubble edge

    // Sizes the app already had that match no step; named rather than rounded (theme inventory §4).
    val pinCellWidth = 92.dp        // three pinned cells per row with SpaceEvenly
    val progressStroke = 2.dp       // every CircularProgressIndicator
    val thumbRadius = 6.dp          // photo, video and file tiles in grids (grid density)
    val cardRadius = 12.dp          // translation card, quoted reply (until C13), the inset warning strip (until C14)
    val avatarBubble = 28.dp        // group sender avatar beside a bubble
    val playOverlay = 42.dp         // play circle over a video bubble
    val saveButton = 40.dp          // save-to-gallery circle beside media; enlarged on purpose
    val timePeekWidth = 84.dp       // how far the list slides to reveal send times
    val linkImageMaxHeight = 170.dp // link-preview hero image cap
    val mediaMaxWidth = 240.dp      // photos and videos in the list stay narrower than text bubbles (C16 revisits)
    val bubbleMaxWidth = 300.dp     // today's fixed bubble cap; C6/C16 move to bubbleMaxWidthFraction
    val attachPanelHeight = 320.dp  // inline attachment picker under the composer
    val selectionBorder = 3.dp      // ring on a selected picker tile
    val playOverlaySmall = 28.dp    // play circle over a picker tile
    val iconTiny = 12.dp            // check inside the picker's 20dp selection badge
    val chipIcon = 14.dp            // InputChip trailing icon; 20 would crowd the chip
    val recipientFieldMinWidth = 96.dp  // keeps the To: field from collapsing behind recipient chips
    val ctaButtonHeight = 52.dp     // full-width call-to-action button ("New FaceTime Call"); fixed, not a minimum like touchTarget
    val ctaRadius = 14.dp           // its corner: a rounded rectangle, not the field pill
    val lockGlyph = 64.dp           // the lock icon on the app-lock screen (AppLock.kt); a hero glyph, not a 20dp inline icon

    // Conversation shortcut icons (ConversationShortcuts.kt): drawn in pixels at the launcher's icon size,
    // as full-bleed adaptive bitmaps. The launcher shows the centre 72/108 of the square (the mask), so the
    // initials are sized against that circle, with avatarInitialsFraction as on the list's avatars.
    const val adaptiveIconMaskFraction = 72f / 108f
    const val silhouetteHeadFraction = 0.19f   // person silhouette for a chat with no initials: head radius
    const val silhouetteBodyFraction = 0.36f   // ... and the shoulders' radius, as shares of the masked circle
}

internal val BubbleShape = RoundedCornerShape(Dimens.bubbleRadius)
internal val FieldShape = RoundedCornerShape(Dimens.fieldRadius)
internal val CtaShape = RoundedCornerShape(Dimens.ctaRadius)
