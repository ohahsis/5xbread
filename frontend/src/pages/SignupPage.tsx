// C-01 가입. 형식 검사는 서버(400 errors[])에 맡기고, 받은 문구를 입력칸 아래에 그대로 보여 준다.
import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { fieldErrorsOf, messageOf } from '../api/problem'
import { signUp } from '../auth/auth'
import s from './Form.module.css'

export function SignupPage() {
  const navigate = useNavigate()
  const [form, setForm] = useState({ phone: '', password: '', name: '', terms: false, privacy: false })
  const [error, setError] = useState<unknown>(null)
  const [submitting, setSubmitting] = useState(false)

  const update = (field: keyof typeof form, value: string | boolean) => setForm((prev) => ({ ...prev, [field]: value }))

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault()
    setSubmitting(true)
    setError(null)
    try {
      await signUp({
        phone: form.phone,
        password: form.password,
        name: form.name,
        agreements: { terms: form.terms, privacy: form.privacy },
      })
      navigate('/login?signedUp=1')
    } catch (e) {
      setError(e)
    } finally {
      setSubmitting(false)
    }
  }

  const fieldErrors = fieldErrorsOf(error)
  return (
    <form className={s.form} onSubmit={onSubmit}>
      <h1>가입</h1>
      {error !== null && <p className="notice error">{messageOf(error)}</p>}
      <label className={s.label}>
        휴대폰 번호
        <input value={form.phone} onChange={(e) => update('phone', e.target.value)} inputMode="numeric" autoComplete="tel" placeholder="01012345678" required />
        {fieldErrors.phone && <span className="field-error">{fieldErrors.phone}</span>}
      </label>
      <label className={s.label}>
        비밀번호
        <input type="password" value={form.password} onChange={(e) => update('password', e.target.value)} autoComplete="new-password" required />
        {fieldErrors.password && <span className="field-error">{fieldErrors.password}</span>}
      </label>
      <label className={s.label}>
        이름
        <input value={form.name} onChange={(e) => update('name', e.target.value)} autoComplete="name" required />
        {fieldErrors.name && <span className="field-error">{fieldErrors.name}</span>}
      </label>
      <label className={s.check}>
        <input type="checkbox" checked={form.terms} onChange={(e) => update('terms', e.target.checked)} />
        이용약관에 동의합니다 (필수)
      </label>
      <label className={s.check}>
        <input type="checkbox" checked={form.privacy} onChange={(e) => update('privacy', e.target.checked)} />
        개인정보 수집·이용에 동의합니다 (필수)
      </label>
      {(fieldErrors['agreements.terms'] || fieldErrors['agreements.privacy']) && (
        <span className="field-error">{fieldErrors['agreements.terms'] ?? fieldErrors['agreements.privacy']}</span>
      )}
      <button className="button" disabled={submitting}>{submitting ? '가입 중…' : '가입하기'}</button>
      <p className={s.footer}>이미 가입하셨나요? <Link to="/login">로그인</Link></p>
    </form>
  )
}
