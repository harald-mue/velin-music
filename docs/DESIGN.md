# Velin Design Language

## Product identity

**Velin** — *Your music. Your network.*

The Android application is a polished first-party client for a person's own library, not a generic dashboard.

## Principles

Velin should feel minimal, focused, calm, technical, precise, premium, privacy-oriented, and intentionally Android-native. Use GrapheneOS and Android system UI as inspiration for restraint and clarity only; do not copy their assets or branding. Familiar navigation and playback patterns may be used, but Velin must not visually imitate Spotify.

## Theme and surfaces

Dark mode is the primary mode. Use near-black graphite surfaces rather than pure black everywhere. Album artwork supplies most visible color. Use accents sparingly and avoid broad random artwork-derived gradients.

Avoid excessive rounded cards, glassmorphism, generic SaaS dashboard styling, and ornamental gradients. Prefer hierarchy, spacing, typography, dividers, surfaces, subtle elevation, and precise geometry.

The implemented phone theme uses near-black graphite backgrounds, restrained ice-blue accents, lightweight display headings, denser semibold content labels, softly rounded artwork, and tonal surfaces only where grouping benefits from them. System bars remain dark with light icons so edge-to-edge content retains clear contrast.

## Iconography and mark

Velin's eventual icon family is custom, monochrome, geometric, simple, recognizable, and consistent in stroke/fill weight on a shared grid. Until that family exists, use coherent Material icons for navigation and actions rather than letters or text glyphs. A music note is not the primary brand mark. Explore an abstract geometric `V` that can also suggest stereo channels, waveform geometry, or a server/client connection.

## Navigation

Initial primary navigation:

```text
Home    Search    Library
```

Show a persistent mini-player only while playback is active. Keep navigation shallow and make server identity and privacy state easy to find.

## Core screens

Plan for onboarding/pairing, home, albums, album detail, artists, artist detail, search, now playing, queue, and settings/server information. Loading, empty, unavailable-artwork, and recoverable-error states are product surfaces, not afterthoughts.

## Playback and audio quality

Playback belongs in a background `MediaLibraryService`, not an Activity. The phone UI uses the Velin design system. Android Auto uses the host-provided driver-safe media interface; Velin supplies hierarchy, metadata, artwork, and playback controls rather than a custom automotive layout. The UI may present useful technical information such as `FLAC`, `MP3`, `24-bit`, `96 kHz`, or `2 channels`, but normal playback controls should remain uncluttered. Original-format streaming and seeking are part of the experience.

## Accessibility

Use readable contrast, content descriptions for controls/artwork, touch targets appropriate to Android, meaningful focus order, reduced-motion behavior, and text that remains usable with larger font settings. Validate designs on small and large screens without making every surface a card.
