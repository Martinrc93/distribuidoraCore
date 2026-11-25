import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter } from 'react-router-dom'
import App from './app/App'
import '@fontsource-variable/inter/wght.css'
import './styles.css'
import { initializeTheme } from './shared/theme'
import { initializeTextSize } from './shared/textSize'
import { ApiError } from './shared/api/client'

initializeTheme()
initializeTextSize()

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: (failureCount, error) => failureCount < 1
        && (error instanceof TypeError || (error instanceof ApiError && error.status >= 500)),
      refetchOnWindowFocus: false,
      staleTime: 30_000,
    },
  },
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter basename={import.meta.env.BASE_URL}>
        <App />
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
)
