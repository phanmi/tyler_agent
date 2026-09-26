import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import './styles/tokens.css'
import './styles/theme.css'
import './styles/layout.css'
import './styles/chat.css'

// Mount the React component tree in index.html at <div id="root">.
// StrictMode repeats some calls in development to expose side-effect bugs.
// Load CSS in order: tokens, base theme, application layout, and chat details.
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
