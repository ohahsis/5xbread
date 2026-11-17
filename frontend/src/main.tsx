import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './styles/global.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <p className="page-message">5XBread</p>
  </StrictMode>,
)
