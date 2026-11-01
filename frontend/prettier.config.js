// The settings match the existing source style, so adopting the formatter only reflows code.
/** @type {import('prettier').Config} */
export default {
  semi: false,
  singleQuote: true,
  printWidth: 120,
  // Git stores LF, but Windows checkouts with core.autocrlf=true hold CRLF. Keeping each file's existing
  // ending lets `format:check` give the same answer on Windows and on the Linux CI runner.
  endOfLine: 'auto',
}
