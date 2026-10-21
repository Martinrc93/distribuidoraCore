import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import '@fontsource-variable/inter/wght.css'
import '../styles.css'
import { initializeTheme } from '../shared/theme'
import { InstanceLauncher } from './InstanceLauncher'

initializeTheme()
createRoot(document.getElementById('root')!).render(<StrictMode><InstanceLauncher /></StrictMode>)
