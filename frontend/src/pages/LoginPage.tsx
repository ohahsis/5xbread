// C-01 로그인. 성공하면 Access Token 은 메모리에(session.ts), Refresh Token 은 서버가 HttpOnly 쿠키로 심는다.
import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { fieldErrorsOf, messageOf } from '../api/problem'
import { useLogin } from '../auth/auth'
import s from './Form.module.css'

export function LoginPage() {
  const login = useLogin()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const [phone, setPhone] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<unknown>(null)
  const [submitting, setSubmitting] = useState(false)

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault()
    setSubmitting(true)
    setError(null)
    try {
      await login({ phone, password })
      navigate(safeNext(params.get('next')), { replace: true })
    } catch (e) {
      setError(e)
    } finally {
      setSubmitting(false)
    }
  }

  const fieldErrors = fieldErrorsOf(error)
  return (
    <form className={s.form} onSubmit={onSubmit}>
      <h1>로그인</h1>
      {params.get('signedUp') && <p className="notice ok">가입했습니다. 로그인해 주세요.</p>}
      {error !== null && Object.keys(fieldErrors).length === 0 && <p className="notice error">{messageOf(error)}</p>}
      <label className={s.label}>
        휴대폰 번호
        <input value={phone} onChange={(e) => setPhone(e.target.value)} inputMode="numeric" autoComplete="tel" placeholder="01012345678" required />
        {fieldErrors.phone && <span className="field-error">{fieldErrors.phone}</span>}
      </label>
      <label className={s.label}>
        비밀번호
        <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required />
        {fieldErrors.password && <span className="field-error">{fieldErrors.password}</span>}
      </label>
      <button className="button" disabled={submitting}>{submitting ? '로그인 중…' : '로그인'}</button>
      <p className={s.footer}>처음이신가요? <Link to="/signup">가입하기</Link></p>
    </form>
  )
}

/** 로그인 뒤 돌아갈 곳. 다른 사이트 주소(//evil.com 등)로는 보내지 않는다 */
function safeNext(next: string | null): string {
  if (next && next.startsWith('/') && !next.startsWith('//')) return next
  return '/breads'
}
