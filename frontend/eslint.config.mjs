import typescript from 'typescript-eslint'

export default [
  { ignores: ['dist*/', 'coverage/', 'test-results/', 'playwright-report/'] },
  {
    files: ['**/*.{ts,tsx}'],
    languageOptions: { parser: typescript.parser },
    rules: {
      'no-constant-condition': ['error', { checkLoops: false }],
      'no-debugger': 'error',
      'no-dupe-else-if': 'error',
      'no-duplicate-case': 'error',
      'no-fallthrough': 'error',
      'no-unsafe-finally': 'error',
    },
  },
]
