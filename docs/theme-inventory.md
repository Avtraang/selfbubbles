# Theme inventory (spec step 1)

Every hard-coded `Color(0x…)`, every `.sp` and every `.dp` in
`app/src/main/java/io/github/avtraang/selfbubbles/*.kt` at the baseline commit,
with the spec name it maps to. No code changes in this step. The template
files under `ui/theme/` (`Color.kt`, `Theme.kt`, `Type.kt` — unused
`MyApplicationTheme`, Purple/Pink swatches) are replaced wholesale in step 2
and are not listed.

Owner decisions applied to the mappings: BlueFill stays **#007AFF**, GreenFill
stays **#34C759**, `primary = primaryContainer = BlueFill`, today's avatar fills
become `avatarFills`, today's `SENDER_COLORS` become `senderNames`, the warning
strip takes the spec's amber pair, title stays "iMessage".

Line numbers refer to the baseline commit `9d76336`.
Since then `MainActivity.kt` has been split by pure moves (SelfBubbles C1–C8b, 2026-10-06) into `ThreadModel.kt`, `ChatVM.kt`, `ListWidgets.kt`, `ThreadListScreen.kt`, `ComposeScreen.kt`, `ConversationScreen.kt`, `InfoScreen.kt`, `Bubble.kt` and `AttachmentPanel.kt`; no tokens changed, so the `MainActivity.kt:NNN` references below name the baseline file only.

## Summary

| Kind | Occurrences | Distinct values |
| --- | --- | --- |
| `Color(0x…)` literals | 25 lines (MainActivity 21, FaceTime 3, FaceTimeScreen 1) | 33 hex values (8 avatar + 10 sender + 15 others) |
| Named `Color.*` constants (Gray, White, Black, LightGray, Transparent) | 58 lines | 5 |
| `.sp` | 41 lines | 10 font sizes (24, 20, 17, 15, 14, 13, 12, 11, 10, 9) + 7 line heights (17, 16, 15, 14, 12, 11, 10) + 1 computed (`size/2.6f`) |
| `.dp` | 155 lines (MainActivity 132, FaceTimeScreen 17, FaceTime 4, VoiceActivity 2) | 38 values |

The spec's "29 colors" = the 33 hex literals minus the four it could not see
(`#0A84FF`, `#121212`, `#AAAAAA`, `#B26A00`); all 33 are accounted for below.

## 1. Colors — `Color(0x…)` literals

| File:line | Value | Used for | Maps to |
| --- | --- | --- | --- |
| MainActivity.kt:130 | `BubbleSent` #007AFF | sent iMessage bubble, FAB, send button, unread dots, swipe "Unread" panel, selection border, SaveButton | `MessagesTheme.colors.bubbleIMessage` (= `colorScheme.primaryContainer`, BlueFill, kept #007AFF) |
| MainActivity.kt:132 | `LinkBlue` #007AFF | header icon tints, text buttons ("Archive", "Reply", "Translate"), links, cursor, ActionChip label | `colorScheme.primary` (Blue text; owner: same BlueFill #007AFF) |
| MainActivity.kt:137 | `bubbleRecv()` #1C1C1E dark / #E9E9EB light | received bubble, reply bar bg, translation card, quoted-reply bg | `MessagesTheme.colors.bubbleReceived` (Gray 2: #2C2C2E / #E5E5EA, spec C7) |
| MainActivity.kt:138 | `textRecv()` White / #000000 | text on received bubble, link card text | `MessagesTheme.colors.onBubbleReceived` (= `colorScheme.onSurface`) |
| MainActivity.kt:139 | `pillBg()` #2A2A2C dark / #F2F2F7 light | via-label pill, reaction chip, ActionChip, picker tiles, file thumb box | `colorScheme.surfaceContainer` (Gray 1) for pills/tiles; reaction chip → `surfaceContainerHighest` (Gray 3, spec C12) |
| MainActivity.kt:142 | `BubbleSms` #34C759 | sent SMS/RCS bubble, SMS dot on avatar, SMS link card | `MessagesTheme.colors.bubbleSms` (GreenFill, kept #34C759) |
| MainActivity.kt:155 | `Color.Black` | AmoledColors.background | `colorScheme.background` (DarkColors) |
| MainActivity.kt:156 | `Color.Black` | AmoledColors.surface | `colorScheme.surface` |
| MainActivity.kt:157 | #1C1C1E | AmoledColors.surfaceVariant | `colorScheme.surfaceVariant` (spec: Gray 3 #3A3A3C) |
| MainActivity.kt:158 | #121212 | AmoledColors.surfaceContainer | `colorScheme.surfaceContainer` (Gray 1 #1C1C1E) |
| MainActivity.kt:159 | #1C1C1E | AmoledColors.surfaceContainerHigh | `colorScheme.surfaceContainerHigh` (Gray 2 #2C2C2E) |
| MainActivity.kt:160 | `Color.White` | AmoledColors.onBackground | `colorScheme.onBackground` |
| MainActivity.kt:161 | `Color.White` | AmoledColors.onSurface | `colorScheme.onSurface` |
| MainActivity.kt:162 | #0A84FF | AmoledColors.primary | `colorScheme.primary` (owner: BlueFill #007AFF) — whole `AmoledColors` scheme is replaced by `DarkColors` in step 3 |
| MainActivity.kt:166 | #5E5CE6, #6C7A89, #8E8E93, #4A6FA5 | `AVATAR_COLORS` 1–4 | `MessageColors.avatarFills` (kept, owner decision) |
| MainActivity.kt:167 | #9A6FB0, #556B8D, #7D7AAF, #667C8A | `AVATAR_COLORS` 5–8 | `MessageColors.avatarFills` (kept) |
| MainActivity.kt:728 | #00897B, #D81B60, #3949AB, #F4511E | `SENDER_COLORS` 1–4 | `MessageColors.senderNames` (kept, owner decision; same list in dark and light) |
| MainActivity.kt:729 | #8E24AA, #43A047, #0097A7, #EF6C00 | `SENDER_COLORS` 5–8 | `MessageColors.senderNames` (kept) |
| MainActivity.kt:730 | #1E88E5, #6D4C41 | `SENDER_COLORS` 9–10 | `MessageColors.senderNames` (kept) |
| MainActivity.kt:968 | #34C759 | swipe panel "Restore" (archived view) | `MessagesTheme.colors.bubbleSms` (GreenFill) |
| MainActivity.kt:969 | #8E8E93 | swipe panel "Archive" | `colorScheme.outline` (light Outline is #8E8E93; dark #636366) |
| MainActivity.kt:1480 | #B26A00 | "Fallback mode" notice text | `MessagesTheme.colors.onWarningContainer` (amber text) |
| MainActivity.kt:1492 | #FFD580 dark / #7A4B00 light | `send_warning` strip text | `MessagesTheme.colors.onWarningContainer` (#FFD600 / #5C3D00, spec C14) |
| MainActivity.kt:1497 | #3A2C0A dark / #FFF3D1 light | `send_warning` strip background | `MessagesTheme.colors.warningContainer` (#332600 / #FFF1C2, spec C14) |
| MainActivity.kt:2587 | #34C759 | SaveButton "done" fill | `MessagesTheme.colors.bubbleSms` (GreenFill) |
| FaceTime.kt:152 | #1C1C1E | answer-screen background (always dark) | `colorScheme.surfaceContainer` (Gray 1) under `MessagesTheme(darkTheme = true)` |
| FaceTime.kt:159 | #34C759 | answer-screen progress spinner | `MessagesTheme.colors.bubbleSms` (GreenFill) |
| FaceTime.kt:161 | #AAAAAA | answer-screen status text | `colorScheme.onSurfaceVariant` (text secondary) |
| FaceTimeScreen.kt:60 | `FtGreen` #34C759 | "New FaceTime Call" button, call icon tint | `MessagesTheme.colors.bubbleSms` (GreenFill) |

## 1b. Colors — named `Color.*` constants (step 5 replaces these too)

| File:line(s) | Value | Used for | Maps to |
| --- | --- | --- | --- |
| MainActivity.kt:131 | `TextSent = Color.White` | text on sent bubbles | `MessagesTheme.colors.onBubbleIMessage` / `onBubbleSms` |
| MainActivity.kt:811, 891, 981, 1220, 1222, 1573, 1945, 2370, 2463, 2509, 2518, 2602, 2606, 2608 | `Color.White` | initials, FAB content, swipe label, send arrow, play glyph, close/check icons, SaveButton glyphs | `onAvatar`, `onPrimaryContainer`, `onBubbleIMessage` as appropriate (all White in both themes) |
| MainActivity.kt:2588 | `Color.White.copy(0.9f)` | SaveButton ring | `MessagesTheme.colors.onScrim.copy(0.9f)` (White in both themes). The button sits beside the media on the list background, so the spec's background-color ring rule (marks over avatars and bubbles) does not apply; a background ring was black-on-black in dark mode (review round 1, finding 1) |
| MainActivity.kt:909, 1006, 1010, 1026, 1048, 1059, 1062, 1073, 1189, 1234, 1261, 1293, 1469, 1516, 1824, 2038, 2045, 2091, 2189, 2194, 2275, 2642 | `Color.Gray` (#888888) | secondary text: previews, times, section headers, placeholders, attach "+", day divider, "Edited" | `colorScheme.onSurfaceVariant` (Text secondary #98989D / #636366, spec A5) |
| MainActivity.kt:2265 | `Color.Gray.copy(0.8f)` | "Tap to hide translation" | `colorScheme.onSurfaceVariant` |
| MainActivity.kt:1217, 1571 | `Color.Gray.copy(0.5f)` | idle send button fill | `colorScheme.surfaceContainerHigh` (Gray 2, spec C5) |
| MainActivity.kt:1189 | `Color.LightGray` | disabled attach "+" | `colorScheme.onSurfaceVariant.copy(alpha = 0.38f)` (Material disabled) |
| MainActivity.kt:990 | `Color.Transparent` | unread dot off | keep `Color.Transparent` (not a palette color) |
| MainActivity.kt:1942, 2341, 2507 | `Color.Black` / `.copy(0.45f)` | play-overlay scrim, fullscreen viewer bg | `colorScheme.scrim` (Black in both themes) |
| FaceTime.kt:156; FaceTimeScreen.kt:194, 196 | `Color.White` | answer-screen title, call-button content | `colorScheme.onSurface` (dark) / `onBubbleSms` |
| FaceTimeScreen.kt:206, 229, 236, 266 | `Color.Gray` | status, header, search icon, address | `colorScheme.onSurfaceVariant` |
| VoiceActivity.kt:84 | `Color.Black` ×2 | ad-hoc `darkColorScheme(background, surface)` | removed in step 3 (`MessagesTheme`) |

## 2. Font sizes — `.sp`

Spec scale: Title 22/28 Bold (`titleLarge`), Name 17/22 Medium (`titleMedium`),
Body 17/22 (`bodyLarge`), Secondary 15/20 (`bodyMedium`, `titleSmall` Medium,
`labelLarge` Medium), Meta 13/18 (`labelMedium`, `bodySmall`), Micro 11/14
Medium (`labelSmall`).

| File:line | Value | Used for | Maps to |
| --- | --- | --- | --- |
| MainActivity.kt:811 | `(size.value / 2.6f).sp` | avatar initials | `Dimens.avatarInitialsFraction` (0.38 ≈ 1/2.6; sized from the avatar, not the type scale) |
| MainActivity.kt:837 | 12 | pinned-thread name | `labelMedium` (13) |
| MainActivity.kt:1006 | 13 | thread preview | `bodyMedium` (15, spec: previews 13→15) |
| MainActivity.kt:1010 | 12 | thread-list time | `labelMedium` (13) |
| MainActivity.kt:1025 | 13 SemiBold | "Conversations" section header | `labelMedium` (13, spec L15 `SectionHeader`) |
| MainActivity.kt:1047 | 13 SemiBold | "Messages" section header | `labelMedium` |
| MainActivity.kt:1059 | 13 | search snippet | `bodyMedium` (15) |
| MainActivity.kt:1062 | 12 | search result time | `labelMedium` (13) |
| MainActivity.kt:1234 | 15 | "To:" label | `bodyMedium` (15) |
| MainActivity.kt:1255 | 15 | recipient BasicTextField textStyle | `bodyLarge` (17, spec: text fields) |
| MainActivity.kt:1261 | 15 | recipient placeholder | `bodyLarge` (matches the field) |
| MainActivity.kt:1293 | 13 | contact address under name | `bodySmall` (13) |
| MainActivity.kt:1428 | 11 / lh 14 | via-label pill in header | `labelMedium` (13/18, spec C4) |
| MainActivity.kt:1464 | 11 SemiBold | "Replying to …" | `labelMedium` (13) |
| MainActivity.kt:1468 | 12 | reply-bar preview | `bodySmall` (13) |
| MainActivity.kt:1480 | 11 | "Fallback mode" notice | `bodyMedium` (15, warning-strip rule) |
| MainActivity.kt:1491 | 12 / lh 16 | `send_warning` strip | `bodyMedium` (15/20, spec C14) |
| MainActivity.kt:1655 | 24 | reaction-picker emoji | `titleLarge` (22) — emoji glyph, −2sp invisible |
| MainActivity.kt:1681 | 15 SemiBold | info-screen `SectionHeader` | `titleSmall` (15 Medium) |
| MainActivity.kt:1807 | 12 | info-screen Tab labels (5 tabs) | `labelMedium` (13); note: `labelLarge` (15) risks wrapping five tabs |
| MainActivity.kt:1824 | 12 | handle under participant name | `bodySmall` (13) |
| MainActivity.kt:2030 | 14 SemiBold | link-card title | `titleSmall` (15 Medium) |
| MainActivity.kt:2037 | 12 / lh 15 | link-card summary | `bodySmall` (13/18) |
| MainActivity.kt:2044 | 11 | link-card host | `labelSmall` (11/14) |
| MainActivity.kt:2091 | 12 Medium | swipe-peek timestamp | `labelMedium` (13) |
| MainActivity.kt:2117 | 11 / lh 12 Medium | group sender name | `labelMedium` (13/18, spec C15) |
| MainActivity.kt:2188 | 10 SemiBold | quoted-reply sender | `labelSmall` (11 Medium) |
| MainActivity.kt:2192–2193 | 12 / lh 15 | quoted-reply text | `bodySmall` (13/18, spec C13) |
| MainActivity.kt:2257 | 13 / lh 17 italic | translation text | `bodySmall` (13/18) |
| MainActivity.kt:2264 | 9 / lh 10 | "Tap to hide translation" | `labelSmall` (11/14) |
| MainActivity.kt:2275 | 10 / lh 11 | "Edited" caption | `labelSmall` (11/14, spec C15 status) |
| MainActivity.kt:2283 | 13 | reaction emoji chip | `labelMedium` (13, spec C12) |
| MainActivity.kt:2464 | 13 SemiBold | "Send N" button in picker | `labelLarge` (15 Medium) |
| MainActivity.kt:2509 | 12 | play glyph on picker video tile | `labelMedium` (13) |
| MainActivity.kt:2535 | 11 Medium | `ActionChip` label | `labelMedium` (13/18). The spec's picker labels are 15 and 13sp and `labelSmall` is reserved for reaction counts and delivery status; the 11sp box was 24sp tall only through Material's inherited bodyLarge line height (review round 1, finding 2) |
| MainActivity.kt:2642 | 11 Medium | `DayDivider` | `labelMedium` (13, spec C2) |
| FaceTimeScreen.kt:196 | 17 | "New FaceTime Call" button text | `bodyLarge` (17) |
| FaceTimeScreen.kt:206 | 14 | call status | `bodyMedium` (15) |
| FaceTimeScreen.kt:213 | 13 | last link | `bodySmall` (13) |
| FaceTimeScreen.kt:229 | 13 | "Call a contact" header | `labelMedium` (13) |
| VoiceActivity.kt:94 | 20 | voice status | `titleMedium` (17 Medium) — no 20sp step; `titleLarge` (22 Bold) is the alternative |

## 3. Sizes — `.dp`

Spec steps: `Spacing` xxs 2 · xs 4 · sm 8 · md 12 · lg 16 · xl 24. Named sizes
in `Dimens` (touchTarget 48, iconSmall 20, hairline 0.5, screenGutter 16,
bubbleGutter 12, avatarRow 48, avatarPinned 64, avatarSmall 40, unreadDot 10,
unreadDotPinned 14, networkDot 10, unreadGutter 26, threadRowMinHeight 72,
searchRowMinHeight 56, listBottomPad 88, bubbleRadius 18, bubblePadH 12,
bubblePadV 7, bubbleMaxWidthFraction 0.83, bubbleGapInRun 2,
bubbleGapBetweenRuns 12, reactionOverlap 8, fieldHeight 40, fieldRadius 20,
fieldBorder 1, sendButton 36, composerEndPad 6).

"NEW" = matches no step and would visibly change a layout if rounded; gets a
named `Dimens` entry in step 7 instead of a rounding.

### MainActivity.kt

| Line | Value | Used for | Maps to |
| --- | --- | --- | --- |
| 776 | 1.5 | ring on the second stacked group photo | `Spacing.xxs` (2dp ring, spec L9) |
| 822 | 92 | `PinItem` cell width | **NEW `Dimens.pinCellWidth`** (3 per row via `SpaceEvenly`) |
| 824 | 6 | pin cell vertical padding | `Spacing.sm` (8, spec L3) |
| 827 | 68 | pinned avatar | `Dimens.avatarPinned` (64) |
| 830 | 14 | unread dot on pin | `Dimens.unreadDotPinned` |
| 835 | 4 | gap avatar → pin label | `Spacing.xs` |
| 906 | 12 h / 4 v | search field outer padding | `Dimens.screenGutter` (16, spec T3) / `Spacing.xs` |
| 918 | 24 | search field corner | `Dimens.fieldRadius` (20) |
| 925 | 8 | pin grid vertical padding | `Spacing.sm` |
| 931 | 92 | pin-row filler spacer | **NEW `Dimens.pinCellWidth`** |
| 978 | 24 | swipe panel horizontal padding | `Spacing.xl` |
| 989 | 8 | unread dot in row | `Dimens.unreadDot` (10, spec L6) |
| 992 | 6 | dot → avatar gap | folded into `Dimens.unreadGutter` (26) by `ThreadRow` (step 8) |
| 993 | 44 | row avatar | `Dimens.avatarRow` (48) |
| 1027 | 16 start / 10 top / 4 bottom | "Conversations" header | `Dimens.screenGutter` / `Spacing.md` / `Spacing.xs` (reference `SectionHeader`) |
| 1031 | 40 | search-result avatar | `Dimens.avatarSmall` |
| 1049 | 16 / 10 / 4 | "Messages" header | as 1027 |
| 1053 | 40 | search-result avatar | `Dimens.avatarSmall` |
| 1074 | 24 | "No results" vertical padding | `Spacing.xl` |
| 1092 | 8 | spacer between pin-menu buttons | `Spacing.sm` |
| 1178 | 8 | compose bottom-bar row padding | `Spacing.xs` (reference `Composer`: start xs, end composerEndPad, top/bottom xs) |
| 1186 | 38 | attach `IconButton` size | `Dimens.touchTarget` (48; Material `IconButton` default) |
| 1191 | 6 | attach → field gap | `Spacing.xs` (reference Composer has no explicit gap; IconButton's own slot) |
| 1195 | 22 | compose field corner | `Dimens.fieldRadius` (20) |
| 1200 | 8 | field → send gap | `Spacing.sm` |
| 1216 | 38 | compose send circle | `Dimens.sendButton` (36 in a 48 slot, spec C5) |
| 1220 | 20 size / 2 stroke | sending spinner | `Dimens.iconSmall` / `Spacing.xxs` (stroke) |
| 1230 | 12 h / 6 v | "To:" FlowRow padding | `Dimens.screenGutter` (16) / `Spacing.sm` |
| 1231 | 6 | recipient chip spacing | `Spacing.sm` |
| 1244 | 14 | chip close icon | **NEW `Dimens.chipIcon`** (InputChip trailing icon; 20 would crowd the chip) |
| 1253 | 96 | recipient field min width | **NEW `Dimens.recipientFieldMinWidth`** |
| 1254 | 12 | recipient field vertical padding | `Spacing.md` |
| 1289 | 40 | contact-hit avatar | `Dimens.avatarSmall` |
| 1314 | 6 | compose history contentPadding | `Spacing.sm` |
| 1432 | 2 | via-pill top padding | `Spacing.xxs` (pill goes away in C4) |
| 1435 | 8 h / 1 v | via-pill inner padding | `Spacing.sm` / removed with the pill (C4) |
| 1458 | 14 start / 6 end,top,bottom | reply bar padding | `Dimens.bubbleGutter` (12) / `Spacing.sm` |
| 1482 | 2 | fallback notice top | `Spacing.xxs` |
| 1494 | 8 start,end / 6 top | warning strip outer | removed — `WarningStrip` is full width (C14) |
| 1496 | 10 | warning strip corner | removed (C14) |
| 1498 | 12 h / 6 v | warning strip inner | `Dimens.screenGutter` (16) / `Spacing.sm` (reference `WarningStrip`) |
| 1501 | 8 | composer row padding | `Spacing.xs` + `Dimens.composerEndPad` (reference `Composer`) |
| 1503 | 38 | sending-spinner slot | `Dimens.touchTarget` |
| 1504 | 22 size / 2 stroke | sending spinner | `Dimens.iconSmall` / `Spacing.xxs` |
| 1512 | 38 | attach `IconButton` | `Dimens.touchTarget` |
| 1520 | 6 | attach → field gap | `Spacing.xs` |
| 1553 | 22 | composer field corner | `Dimens.fieldRadius` (20, spec C8) |
| 1563 | 8 | field → send gap | `Spacing.sm` |
| 1570 | 38 | send circle | `Dimens.sendButton` (36) |
| 1617 | 84 | max timestamp-peek drag | **NEW `Dimens.timePeekWidth`** |
| 1659 | 4 | divider padding in reaction dialog | `Spacing.xs` |
| 1682 | 14 h / 8 v | info `SectionHeader` | `Dimens.screenGutter` (16) / `Spacing.sm` |
| 1819 | 40 | participant avatar | `Dimens.avatarSmall` |
| 1840–1842 | 2 ×3 | photo grid spacing/padding | `Spacing.xxs` |
| 1853 | 6 | photo tile corner | **NEW `Dimens.thumbRadius`** |
| 1862–1864 | 2 ×3 | video grid spacing/padding | `Spacing.xxs` |
| 1869 | 6 | video tile corner | **NEW `Dimens.thumbRadius`** |
| 1890 | 48 size / 6 corner | file thumbnail box | `Dimens.avatarRow` (48) / **NEW `Dimens.thumbRadius`** |
| 1934 | 32 | `EmptyTab` padding | `Spacing.xl` (24; centered text, no layout impact) |
| 1942 | 42 | `PlayOverlay` circle | **NEW `Dimens.playOverlay`** |
| 2001 | 16 | link-card corner | `Dimens.bubbleRadius` (18, `BubbleShape`, spec C16) |
| 2002 | 300 | link-card max width | `Dimens.bubbleMaxWidthFraction` (0.83 of the gutter-inset list = 78% of the screen = 300 on a 384dp screen) |
| 2021 | 170 | link image max height | **NEW `Dimens.linkImageMaxHeight`** |
| 2026 | 12 h / 8 v | link-card text padding | `Dimens.bubblePadH` / `Dimens.bubblePadV` (7) |
| 2039 | 2 | summary top | `Spacing.xxs` |
| 2046 | 3 | host top | `Spacing.xs` |
| 2073, 2089 | 0 | `peek` default / compare | literal zero, no mapping |
| 2096 | 18 | peek timestamp end padding | `Spacing.lg` (16) |
| 2101 | 12 start,end | bubble row gutter | `Dimens.bubbleGutter` |
| 2102 | 3 / 1 | top: first-in-run / in-run | `Dimens.bubbleGapBetweenRuns / 2` (6) / `Dimens.bubbleGapInRun / 2` (1) (spec C3: 12 between runs, 2 in run) |
| 2103 | 3 / 1 | bottom: last-in-run / in-run | as 2102 |
| 2108–2109 | 28 | group sender avatar beside bubble | **NEW `Dimens.avatarBubble`** |
| 2110 | 6 | avatar → bubble gap | `Spacing.sm` |
| 2119 | 6 start / 1 bottom | sender name padding | `Dimens.bubblePadH` (12, reference `MessageRow`) / `Spacing.xxs` |
| 2129 | 240 | image max width | `Dimens.bubbleMaxWidthFraction` (spec C16 `PhotoRow`); if today's narrower 240 cap must stay: **NEW `Dimens.mediaMaxWidth`** |
| 2130 | 14 | image corner | `Dimens.bubbleRadius` (18, `MediaFrame`) |
| 2147 | 240 | video max width | as 2129 |
| 2148 | 14 | video corner | `Dimens.bubbleRadius` |
| 2165 | 16 | file-attachment bubble corner | `Dimens.bubbleRadius` |
| 2166 | 300 | file-attachment max width | `Dimens.bubbleMaxWidthFraction` |
| 2173 | 12 h / 8 v | file-attachment padding | `Dimens.bubblePadH` / `Dimens.bubblePadV` |
| 2178 | 3 | gap after an attachment | `Spacing.xs` |
| 2183 | 12 | quoted-reply corner | removed by `ReplyQuote` (bar + text, C13); interim **NEW `Dimens.cardRadius`** |
| 2184 | 260 max / 2 bottom | quoted-reply width / gap | `Dimens.bubbleMaxWidthFraction` / `Spacing.xs` (4 above the bubble, C13) |
| 2186 | 10 h / 5 v | quoted-reply padding | removed by `ReplyQuote`; interim `Spacing.sm` / `Spacing.xs` |
| 2214 | 16 | text bubble corner | `Dimens.bubbleRadius` (18, spec C6) |
| 2216 | 300 | text bubble max width | `Dimens.bubbleMaxWidthFraction` |
| 2222 | 276 | `HuggingText` max width | derived: bubble max − 2 × `Dimens.bubblePadH` |
| 2223 | 12 h / 8 v | text bubble padding | `Dimens.bubblePadH` / `Dimens.bubblePadV` (7, spec C6) |
| 2232 | 2 | chip row top | `Spacing.xxs` |
| 2241 | 6 | chip gap | `Spacing.sm` |
| 2249 | 12 | translation card corner | **NEW `Dimens.cardRadius`** |
| 2250 | 3 top / 300 max | translation card | `Spacing.xs` / `Dimens.bubbleMaxWidthFraction` |
| 2251 | 12 | translation card clip | **NEW `Dimens.cardRadius`** |
| 2254 | 10 h / 6 v | translation card padding | `Dimens.bubblePadH` / `Dimens.bubblePadV` |
| 2266 | 3 | "Tap to hide" top | `Spacing.xs` |
| 2276 | 6 start,end | "Edited" padding | `Spacing.xxs` end-aligned (spec C15 status: 2dp gap) |
| 2280 | 12 | reaction chip corner | `CircleShape` on a 26dp-tall chip (reference `ReactionChip`) — **NEW `Dimens.reactionChipHeight`** if a fixed height is wanted |
| 2281 | 2 | reaction chip top | replaced by `Dimens.reactionOverlap` (8, rides up over the bubble, C12) |
| 2284 | 8 h / 3 v | reaction chip padding | `Spacing.sm` / `Spacing.xs` (reference `ReactionChip`) |
| 2368 | 8 | fullscreen close-button padding | `Spacing.sm` |
| 2445 | 320 | attachment panel height | **NEW `Dimens.attachPanelHeight`** |
| 2448 | 10 h / 6 v | picker chip row padding | `Spacing.md` / `Spacing.sm` |
| 2451, 2453 | 8 | chip gaps | `Spacing.sm` |
| 2458–2459 | 15 | "Send N" corner | `CircleShape` (pill) |
| 2465 | 14 h / 6 v | "Send N" padding | `Spacing.lg` / `Spacing.sm` |
| 2477 | 24 size / 2 stroke | picker loading spinner | `Dimens.iconSmall` (20) / `Spacing.xxs` |
| 2481–2483 | 2 ×3 | picker grid spacing/padding | `Spacing.xxs` |
| 2489 | 6 | picker tile corner | **NEW `Dimens.thumbRadius`** |
| 2494 | 3 border / 6 corner | selected tile | **NEW `Dimens.selectionBorder`** / **NEW `Dimens.thumbRadius`** |
| 2506 | 28 | video play circle on tile | **NEW `Dimens.playOverlaySmall`** (or `Dimens.avatarBubble`, also 28) |
| 2513 | 4 pad / 18 size | selected check badge | `Spacing.xs` / `Dimens.iconSmall` (20) |
| 2518 | 12 | check icon in badge | **NEW `Dimens.iconTiny`** |
| 2532–2533 | 11 | `ActionChip` corner | `CircleShape` (pill) |
| 2536 | 9 h / 4 v | `ActionChip` padding | `Spacing.sm` / `Spacing.sm` (18sp line + 2 x 8 = a 34dp pill, not under the 32dp it had; `Spacing.xs` with `labelSmall` made a 22dp tap target — review round 1, finding 2) |
| 2586 | 6 pad / 40 size | `SaveButton` | `Spacing.sm` / **NEW `Dimens.saveButton`** (40; "bigger" was deliberate) |
| 2588 | 2 | `SaveButton` ring | `Spacing.xxs` (2dp ring) |
| 2602 | 18 size / 2 stroke | `SaveButton` spinner | `Dimens.iconSmall` / `Spacing.xxs` |
| 2606 | 22 | `SaveButton` check icon | `Dimens.iconSmall` (20) |
| 2608 | 22 | `DownloadGlyph` size | `Dimens.iconSmall` (20) |
| 2644 | 14 top / 6 bottom | `DayDivider` padding | `Spacing.lg` / `Spacing.xs` (reference `DateSeparatorRow`) |

### FaceTime.kt (answer screen)

| Line | Value | Used for | Maps to |
| --- | --- | --- | --- |
| 152 | 32 | screen padding | `Spacing.xl` (24; centered column) |
| 158, 160 | 12 | spacers around the spinner | `Spacing.md` |
| 164 | 8 | spacer before Close | `Spacing.sm` |

### FaceTimeScreen.kt

| Line | Value | Used for | Maps to |
| --- | --- | --- | --- |
| 190 | 14 | call-button corner | **NEW `Dimens.ctaRadius`** (14) via `CtaShape`; `fieldRadius` (20) made a near-pill of a 48dp button, and nothing in the spec restyles this screen (review round 1, finding 3) |
| 191 | 16 h / 12 v | call-button outer padding | `Dimens.screenGutter` / `Spacing.md` |
| 192 | 52 | call-button height | **NEW `Dimens.ctaButtonHeight`** (52); `touchTarget` is a minimum in the spec, and this is a fixed height (review round 1, finding 3) |
| 195 | 10 | icon → label gap | `Spacing.sm` |
| 201 | 16 h / 4 v | busy row padding | `Dimens.screenGutter` / `Spacing.xs` |
| 204 | 18 size / 2 stroke | busy spinner | `Dimens.iconSmall` / `Spacing.xxs` |
| 205 | 12 | spinner → text gap | `Spacing.md` |
| 212 | 16 h / 4 v | last-link column padding | `Dimens.screenGutter` / `Spacing.xs` |
| 215 | 6 top / 8 spacedBy | link buttons row | `Spacing.sm` / `Spacing.sm` |
| 219 | 16 | share icon in button | `Dimens.iconSmall` (20) |
| 220 | 6 | icon → "Share" gap | `Spacing.sm` |
| 228 | 8 | divider vertical padding | `Spacing.sm` |
| 230 | 16 | "Call a contact" start | `Dimens.screenGutter` |
| 234 | 12 h / 8 v | contact search field padding | `Dimens.screenGutter` (16) / `Spacing.sm` |
| 245 | 24 | search field corner | `Dimens.fieldRadius` (20) |
| 254, 268 | 40 | result avatars | `Dimens.avatarSmall` |

### VoiceActivity.kt

| Line | Value | Used for | Maps to |
| --- | --- | --- | --- |
| 88 | 32 | screen padding | `Spacing.xl` |
| 91 | 20 | spinner ↔ status gap | `Spacing.lg` (16) |

## 4. New `Dimens` entries proposed for step 7

| Name | Value | Why not a rounding |
| --- | --- | --- |
| `pinCellWidth` | 92 | three pins per row with `SpaceEvenly`; 88/96 shifts the grid |
| `chipIcon` | 14 | `InputChip` trailing icon; 20 crowds the chip |
| `recipientFieldMinWidth` | 96 | keeps the To: field from collapsing behind chips |
| `timePeekWidth` | 84 | gesture travel for the timestamp peek |
| `thumbRadius` | 6 | photo/video/file tile corner (grid density) |
| `playOverlay` / `playOverlaySmall` | 42 / 28 | play circles over video thumbnails |
| `linkImageMaxHeight` | 170 | link-preview image cap |
| `avatarBubble` | 28 | group sender avatar beside a bubble |
| `cardRadius` | 12 | translation card (and quoted reply until C13) |
| `attachPanelHeight` | 320 | attachment picker sheet |
| `selectionBorder` | 3 | selected tile ring in the picker |
| `iconTiny` | 12 | check inside the 18/20dp badge |
| `saveButton` | 40 | SaveButton circle; was enlarged on purpose |
| `ctaButtonHeight` / `ctaRadius` | 52 / 14 | the full-width "New FaceTime Call" button; a fixed height and a rounded rectangle, kept as today (review round 1, finding 3) |
| `mediaMaxWidth` | 240 | only if images should stay narrower than text bubbles |
| `reactionChipHeight` | 26 | only if the chip gets a fixed height instead of padding |

## 5. Other hard-coded values seen while listing (for later steps)

- `RoundedCornerShape(50)` at MainActivity.kt:1433 (via pill, percent) — goes away with C4.
- `size * 0.78f`, `size * 0.52f`, `size * 0.34f`, `size * 0.05f` (MainActivity.kt:770, 775, 788, 791): stacked-photo and SMS-dot fractions; spec L9 gives 74% / 52%; the SMS badge becomes `NetworkDot` (`Dimens.networkDot` + 2dp ring).
- `strokeWidth = 2.dp` on every `CircularProgressIndicator` is listed as `Spacing.xxs`; a `Dimens.progressStroke` would read better.

## 6. Tokens added after the migration

| Token | Value | Used for |
| --- | --- | --- |
| `MessageColors.pdfPage` | White in both themes | the paper behind a rendered page in the built-in PDF viewer (`PdfViewer.kt`); a PDF page is white whatever the app theme. The viewer adds no `Dimens` entry: its gaps are `Spacing` steps and its spinner uses `Dimens.progressStroke` |
| `Dimens.lockGlyph` | 64 | the Lock icon on the app-lock screen (`AppLock.kt`): a hero glyph centered on an otherwise empty screen, so neither `iconSmall` (20, inline) nor `avatarPinned` (64, an avatar size) names it. The lock screen and the Settings screen (`SettingsScreen.kt`) add nothing else: gaps are `Spacing` steps, rows use `Dimens.threadRowMinHeight` / `Dimens.touchTarget`, gutters `Dimens.screenGutter`, and every color is a `colorScheme` role |
| — (no new token) | — | the built-in video player (`VideoPlayer.kt`) draws on `colorScheme.scrim` (Black in both themes, as the fullscreen image viewer does) with `MessageColors.onScrim` for its text and glyphs, so the player surface is black whatever the app theme. Its bars use `scrim.copy(alpha = 0.6f)` like the PDF page pill, its gaps are `Spacing` steps and its reserved bottom row is `Dimens.touchTarget` |
| — (no new token) | — | history paging in the conversation (`HistoryPaging.kt`, `ChatVM.loadOlder`): the "Loading older messages" row at the oldest end of the list is a centred `CircularProgressIndicator` at `Dimens.iconSmall` with `Dimens.progressStroke`, padded `Spacing.sm`, in the indicator's default `colorScheme.primary`; nothing else is drawn |
| — (no new token) | — | search in this chat (`InChatSearch.kt`, the Conversation screen's search mode): the top bar swaps to the thread list's own `SearchField` with a Close `IconButton` (48dp), and the Search action is a `colorScheme.primary` tinted icon like Info. The results overlay sits on `colorScheme.surface` and reuses `SearchResultRow` (`Dimens.searchRowMinHeight`, `Dimens.avatarSmall`, `Dimens.screenGutter`) and `SearchResultDivider`; its status lines ("Searching…", "No results") are `bodyMedium` in `onSurfaceVariant` padded `Spacing.xl`, the failure line in `colorScheme.error`. The "Loading older messages…" strip is `surfaceContainer` with the spinner at `Dimens.iconSmall` + `Dimens.progressStroke`, a `Dimens.hairline` `outlineVariant` divider and a `TextButton` Cancel. The jumped-to bubble is tinted `colorScheme.primary` at `HIGHLIGHT_ALPHA` (0.35) fading to 0 over `HIGHLIGHT_MS` (1.5 s); both constants live in `InChatSearch.kt` as timing/opacity, not sizes or colors |
| — (no new token) | — | the Files tab of the info screen plays audio attachments inline with the same `AudioBubble` as the chat, in the received pair (`bubbleReceived` / `onBubbleReceived`, so it reads on the list surface in both themes), inside a `ListItem` whose supporting line is the send time; non-audio files keep the thumbnail `ListItem` |
| — (no new token) | — | receiving from the share sheet (`ShareScreens.kt`): the chat picker is the thread list's own pieces (`SearchField`, `PinItem`, `ThreadRow`, `SearchResultRow`, the dividers and `SectionHeader`), so it adds nothing. The "Send to …?" sheet is a Material `AlertDialog`; each item's thumbnail is a `Dimens.avatarRow` (48) tile with the grid's `Dimens.thumbRadius` corner on `colorScheme.surfaceContainer`, the play glyph over a video frame is `Dimens.iconSmall` in `MessageColors.onScrim` (white on any picture, as over the chat's video bubbles) or `onSurfaceVariant` on the plain tile, the extension label is `labelMedium` secondary text, the name `bodyLarge` and the size `bodyMedium` secondary; gaps are `Spacing` steps. `ShareActivity` draws nothing (the platform's translucent theme) |
| — (no new token) | — | the contact card for a vCard attachment (`ContactCardBubble.kt`, in the chat and in the Files tab) takes the bubble's own pair like the voice-message player: `bubbleIMessage`/`bubbleSms` with their `on…` colors when sent, `bubbleReceived`/`onBubbleReceived` when received (the Files tab uses the received pair). Name `bodyLarge`, secondary line `labelMedium`, the two text buttons `labelLarge`, all in the bubble's text color at full alpha (a disabled button at Material's 0.38). The avatar is `Dimens.avatarRow` (48) with the vCard photo or `InitialsAvatar` on an `avatarFills` color; the photo is decoded at `Dimens.avatarPinned` (64) pixels so the Details dialog's copy is sharp. The card is capped at `Dimens.bubbleMaxWidth`, padded `Dimens.bubblePadH`, buttons `Dimens.touchTarget` tall; the loading spinner is `Dimens.iconSmall` + `Dimens.progressStroke`; gaps are `Spacing` steps. The Details dialog is a Material `AlertDialog`: labels `labelMedium` in `onSurfaceVariant`, values `bodyLarge` in `primary` when tapping them does something (call, mail, map, browser) and `onSurface` otherwise |
| `Dimens.adaptiveIconMaskFraction` | 72/108 | conversation shortcuts (`ConversationShortcuts.kt`): the launcher icon is a full-bleed adaptive bitmap drawn in pixels at `ShortcutManager`'s icon size; the launcher's mask shows the centre 72 of 108, so the initials are sized against that circle with the list's `avatarInitialsFraction`. Fill is `MessageColors.avatarFills` picked by the raw `chat_name`'s hash and the initials come from the raw `chat_name` too, exactly as the list's `Avatar` keys `InitialsAvatar` (so a nameless chat gets the silhouette, not a "C"), the glyph `MessageColors.onAvatar`; the palette is resolved from the phone's night mode (the fills are the same in both themes). A contact photo or the relay's group icon (capped at 2 MiB, kept decoded across re-publishes) is centre-cropped square instead. The wait screen a direct-share target shows while the list loads (`ShareWaitScreen`, `ShareScreens.kt`) is the picker's own top bar over a centred `CircularProgressIndicator` at `Dimens.iconSmall` + `Dimens.progressStroke`; no new token |
| `Dimens.silhouetteHeadFraction` / `Dimens.silhouetteBodyFraction` | 0.19 / 0.36 | the person silhouette (head and shoulders, drawn with `Canvas`) on a shortcut icon for a chat with no initials, where the list shows the Person icon; shares of the masked circle. The Settings row for the privacy switch reuses `SwitchRow` and adds nothing |
| — (no new token) | — | the relay form (`RelaySetupScreen.kt`: the first-run "Connect to your relay" screen and the Relay section of Settings) is Material `OutlinedTextField`s in their default shape with `isError` + `supportingText` for the validator's messages, the Settings screen's own `SwitchRow`, `SettingsHeader` and `Note` (now `internal`), a `TextButton` Show/Hide on the two masked fields (`material-icons-core` has no Visibility glyph, and no vector was added), and the standard `Button`/`OutlinedButton`/`TextButton`. Gutters are `Dimens.screenGutter`, gaps `Spacing` steps, the Test-connection spinner `Dimens.iconSmall` + `Dimens.progressStroke`; notes are `bodySmall` in `onSurfaceVariant`, refusals in `colorScheme.error` |
| — (no new token) | — | the inline voice-message player (`AudioBubble.kt`) sits inside a bubble and takes the bubble's own pair: `bubbleIMessage`/`bubbleSms` with their `on…` colors when sent, `bubbleReceived`/`onBubbleReceived` when received, so it reads in both themes. The label and the elapsed/total clock are the text color at full alpha (the clock is the bubble's primary information: `labelMedium`, the timestamp size, since 11sp at 85% on iMessage blue fell to about 3.3:1); only the idle (disabled) slider's thumb and active track take `alpha = 0.85f`, and the inactive track `0.3f` (the link card's pattern). Sizes: the play button is `Dimens.touchTarget` (48) with the glyph at the icon's own 24, its spinner `Dimens.iconSmall` + `Dimens.progressStroke`, the bubble cap `Dimens.bubbleMaxWidth`, the end padding `Dimens.bubblePadH`, the rest `Spacing` steps. The Pause glyph is a new vector in `ui/theme/Icons.kt` (`material-icons-core` has PlayArrow but no Pause) |
| — (no new token) | — | the Features section of Settings (`SettingsScreen.kt`, over the switches in `Features.kt`) is four more `SwitchRow`s under a `SettingsHeader` with a `Note`, so it adds nothing: rows `Dimens.threadRowMinHeight`, gutters `Dimens.screenGutter`, gaps `Spacing` steps, titles `bodyLarge`, subtitles `bodyMedium` in `onSurfaceVariant`. The map page's relay proxy (`RelayWebAssets`, `MapScreen.kt`) draws nothing |
| — (no new token) | — | Edit and Undo Send (`EditUnsend.kt`, the Conversation screen): the two entries in the long-press panel are `TextButton`s like "Reply" (full width, the default `colorScheme.primary` label). The "Editing message" banner is the "Replying to" banner's own layout: `bubbleReceived` at 0.5 alpha, padded `Dimens.bubbleGutter` / `Spacing.sm`, a `labelMedium` SemiBold title in `primary`, the message's text in `bodySmall` `onSurfaceVariant` on one line, and a Close `IconButton`. In edit mode the send button keeps its circle and colors and shows the core `Check` icon at `Dimens.iconSmall`; while the edit is on its way a `CircularProgressIndicator` at `Dimens.iconSmall` + `Dimens.progressStroke` stands in the button's slot (`Dimens.sendButton` inside the 48dp minimum). The attach button is disabled meanwhile, its hand-set `onSurfaceVariant` tint at Material's 0.38. "Unsending…" under a bubble is `labelSmall` in `onSurfaceVariant` padded `Spacing.xs`, exactly as "Edited" (which is no longer drawn under one of the owner's own messages with nothing left to show: `showsEditedCaption`). The banner's two lines and the "Unsending…" line are polite live regions for TalkBack, the `WarningStrip` pattern; no visual change |
