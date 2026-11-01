import js from '@eslint/js'
import { defineConfig, globalIgnores } from 'eslint/config'
import reactHooks from 'eslint-plugin-react-hooks'
import globals from 'globals'
import tseslint from 'typescript-eslint'

// Formatting belongs to Prettier; these presets contain no stylistic rules, so the two tools do not overlap.
export default defineConfig(
  globalIgnores(['dist/', 'out/', 'out2/', 'logs/']),
  js.configs.recommended,
  {
    // Type-aware rules catch unhandled promises in effects and event handlers, which plain syntax rules miss.
    files: ['**/*.{ts,tsx}'],
    extends: [tseslint.configs.recommendedTypeChecked],
    languageOptions: {
      globals: globals.browser,
      parserOptions: {
        // tsconfig.json covers src/ and tests/; the root tool configs are checked through a default project.
        projectService: { allowDefaultProject: ['vite.config.ts', 'vitest.config.ts'] },
        tsconfigRootDir: import.meta.dirname,
      },
    },
  },
  {
    // Classic hook rules only. The plugin's React Compiler rules (set-state-in-effect and others) are not
    // enabled yet: satisfying them means restructuring how components load data, tracked as follow-up work.
    files: ['src/**/*.{ts,tsx}', 'tests/**/*.{ts,tsx}'],
    plugins: { 'react-hooks': reactHooks },
    rules: {
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'error',
    },
  },
  {
    files: ['tests/**/*.{ts,tsx}'],
    rules: {
      // `await act(async () => { ... })` is deliberately async even when the callback awaits nothing:
      // the async form makes act flush promise reactions (resolved or rejected requests) before returning.
      '@typescript-eslint/require-await': 'off',
    },
  },
  {
    // Electron main/preload, build scripts, and Node test runners are CommonJS on Node.
    files: ['**/*.cjs'],
    languageOptions: {
      sourceType: 'commonjs',
      globals: globals.node,
    },
  },
)
