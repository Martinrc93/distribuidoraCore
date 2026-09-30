# Repository instructions

## Required UI reference

- Before planning, implementing, reviewing, or modifying any frontend page,
  component, layout, style, or UI interaction, read
  [the UI style guide](docs/development/ui-style-guide.md).
- Inspect the shared components and the relevant selectors in
  `frontend/src/styles.css` before choosing an implementation. Use Tailwind CSS
  utilities and the existing semantic patterns defined with `@apply`; preserve
  exact design values and responsive breakpoints. Reuse the guide's
  patterns for buttons, tables, forms, dialogs, feedback, and responsive layouts.
- Explicit user requirements take precedence. If a requested change introduces
  or changes a reusable UI pattern, update the guide in the same change and keep
  related documentation consistent. Do not treat a feature-specific variant as
  a new global default.
- Distinguish implemented behavior from requirements that still need work. Do
  not copy known accessibility gaps from an existing screen into new UI.

## Changes and communication

- Preserve unrelated working-tree changes.
- Use conventional commits. Never add `Co-Authored-By` or AI attribution.
- Keep replies concise and match the user's language. Ask at most one question
  at a time and wait for the answer before continuing.
- Verify technical claims against code or documentation before stating them.
