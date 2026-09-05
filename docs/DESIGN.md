# Velin Design Language

## Product identity

**Velin** — *Your music. Your network.*

The Android application is a polished first-party client for a person's own library, not a generic dashboard.

## Principles

Velin should feel minimal, focused, calm, technical, precise, premium, privacy-oriented, and intentionally Android-native. Use GrapheneOS and Android system UI as inspiration for restraint and clarity only; do not copy their assets or branding. Familiar navigation and playback patterns may be used, but Velin must not visually imitate Spotify.

## Theme and surfaces

Dark mode is the primary mode. Use near-black graphite surfaces rather than pure black everywhere. Album artwork supplies most visible color. Use accents sparingly and avoid broad random artwork-derived gradients.

Avoid excessive rounded cards, glassmorphism, generic SaaS dashboard styling, and ornamental gradients. Prefer hierarchy, spacing, typography, dividers, surfaces, subtle elevation, and precise geometry.

The implemented phone theme uses the original near-black graphite `#111214` for the window, scaffold, top bar, mini-player, and navigation chrome. Material surface-container tokens share that same graphite so elevation overlays cannot introduce navy or blue bands. Ice-blue is reserved for active controls, seek progress, and the current track.

The administration frontend uses the same graphite palette and geometric Velin mark. Data tables keep headers and rows on consistent heights, vertically center controls, render recent timestamps as compact browser-localized relative times with exact local values in tooltips, and use horizontal overflow rather than irregular wrapping when space is constrained. Long paths and diagnostics may wrap safely. Completed scans and active devices use a compact green checkmark instead of repeated status text. Active scans use an indeterminate ice-blue bar and live counters because a truthful percentage is unavailable until filesystem discovery finishes.

## Iconography and mark

Velin's icon family is custom, monochrome, geometric, simple, recognizable, and consistent in stroke/fill weight on a shared grid. The abstract geometric `V` used by the Android launcher is also the administration header mark and browser icon. Use coherent Material icons for Android navigation and actions rather than letters or text glyphs. A music note is not the primary brand mark.

## Navigation

Initial primary navigation:

```text
Home    Queue    Library
```

Library contains Albums, Artists, Tracks, and Search. Queue is a primary destination for the current Media3 playback queue, with Clear plus one device-local Save/Load slot. Missing tracks from a loaded queue appear greyed and are dropped on the next save. Show a persistent mini-player only while playback is active. Server identity lives in the top-bar status indicator and an information dialog, not as a full-width Home banner.

On compact portrait screens use a bottom navigation bar. On landscape and expanded widths use a navigation rail and split album, artist, track, and Now Playing layouts so artwork and lists sit side by side.

## Core screens

Plan for onboarding/pairing, home, albums, album detail, artists, artist detail, search, now playing, queue, and settings/server information. Loading, empty, unavailable-artwork, and recoverable-error states are product surfaces, not afterthoughts.

## Playback and audio quality

Playback belongs in a background `MediaLibraryService`, not an Activity. The phone UI uses the Velin design system. Album and artist details expose Play (replace the queue) and Add to queue (append the bounded track list). Android Auto uses the host-provided driver-safe media interface; Velin supplies hierarchy, metadata, artwork, and playback controls rather than a custom automotive layout. The UI may present useful technical information such as `FLAC`, `MP3`, `24-bit`, `96 kHz`, or `2 channels`, but normal playback controls should remain uncluttered. Original-format streaming and seeking are part of the experience.

## Accessibility

Use readable contrast, content descriptions for controls/artwork, touch targets appropriate to Android, meaningful focus order, reduced-motion behavior, and text that remains usable with larger font settings. Validate designs on small and large screens without making every surface a card.
