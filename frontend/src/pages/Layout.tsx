import { Link, Navigate, NavLink, Outlet, useLocation, useNavigate } from 'react-router'
import { useLogout, useSession } from '../auth/auth'
import s from './Layout.module.css'

export function Layout() {
  const session = useSession()
  const logout = useLogout()
  const navigate = useNavigate()
  const location = useLocation()
  const here = location.pathname + location.search
  const loginLink = location.pathname === '/login' || location.pathname === '/signup' ? '/login' : `/login?next=${encodeURIComponent(here)}`

  const onLogout = async () => {
    await logout()
    navigate('/breads')
  }

  return (
    <div className={s.page}>
      <header className={s.header}>
        <Link to="/breads" className={s.logo}>5XBread</Link>
        <nav className={s.nav}>
          <NavLink to="/breads">빵 예약</NavLink>
          {session && <NavLink to="/reservations" end={false}>내 예약</NavLink>}
          {session
            ? <button type="button" className="link-button" onClick={onLogout}>로그아웃</button>
            : <NavLink to={loginLink}>로그인</NavLink>}
        </nav>
      </header>
      {session?.mustChangePassword && (
        <p className="notice warning">
          임시 비밀번호로 로그인했습니다. 비밀번호를 바꾸기 전에는 예약할 수 없습니다. (비밀번호 변경 화면은 아직 없습니다)
        </p>
      )}
      <main className={s.main}>
        <Outlet />
      </main>
    </div>
  )
}

/** 로그인이 필요한 화면. 로그인하지 않았으면 로그인 화면으로 보내고, 로그인 뒤 돌아온다 */
export function RequireLogin() {
  const session = useSession()
  const location = useLocation()
  if (!session) {
    return <Navigate to={`/login?next=${encodeURIComponent(location.pathname + location.search)}`} replace />
  }
  return <Outlet />
}
