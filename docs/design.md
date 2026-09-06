# SecureChat Android design

Use the existing Compose theme and components in `libraries/designsystem` and `libraries/compound`
before adding new colors, typography, dimensions, or icons. This keeps light/dark themes,
accessibility, and screenshot baselines consistent.

For UI changes:

- Use theme tokens instead of literal colors.
- Reuse existing text styles and spacing.
- Prefer repository vector drawables for icons and WebP for photographic raster assets.
- Provide content descriptions for meaningful imagery.
- Verify compact screens, font scaling, light/dark themes, and accessibility contrast.
- Add or update Compose previews and visual baselines.

The Compound sources are an inherited technical design-system dependency. Generated assets live in
`libraries/compound`; their package and resource identifiers remain stable for compatibility. To
refresh tokens, set `COMPOUND_TOKENS_REPOSITORY` to an approved source and run
`tools/compound/import_tokens.sh`. Review every generated visual change before committing it.

SecureChat-owned design files may be linked from implementation comments when access and retention
are controlled by the SecureChat project. Do not add private design URLs, parent-company accounts,
or third-party credentials to public documentation.
