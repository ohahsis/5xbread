import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { createBrowserRouter, Navigate, RouterProvider } from 'react-router'
import { ApiError } from './api/problem'
import { SessionRestorer } from './auth/auth'
import { Layout, RequireLogin } from './pages/Layout'
import { BreadListPage } from './pages/BreadListPage'
import { LoginPage } from './pages/LoginPage'
import { MyReservationsPage } from './pages/MyReservationsPage'
import { ReservationDetailPage } from './pages/ReservationDetailPage'
import { SignupPage } from './pages/SignupPage'
import './styles/global.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // 4xx 는 다시 보내도 같은 결과라 재시도하지 않는다. 연결 실패·5xx 만 한 번
      retry: (failureCount, error) => failureCount < 1 && (!(error instanceof ApiError) || error.status === 0 || error.status >= 500),
    },
  },
})

const router = createBrowserRouter([
  {
    element: <Layout />,
    children: [
      { index: true, element: <Navigate to="/breads" replace /> },
      { path: 'breads', element: <BreadListPage /> },
      { path: 'login', element: <LoginPage /> },
      { path: 'signup', element: <SignupPage /> },
      {
        element: <RequireLogin />,
        children: [
          { path: 'reservations', element: <MyReservationsPage /> },
          { path: 'reservations/:reservationId', element: <ReservationDetailPage /> },
        ],
      },
      { path: '*', element: <p className="page-message">없는 화면입니다</p> },
    ],
  },
])

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <SessionRestorer>
        <RouterProvider router={router} />
      </SessionRestorer>
    </QueryClientProvider>
  </StrictMode>,
)
