import { useEffect, useState } from 'react';
import { RENEW_BEFORE_MS, iam } from './auth/iam';
import { loadSession, onSessionChange, type Session } from './auth/session';
import { DoctorDesk } from './components/DoctorDesk';
import { AdminDesk } from './components/AdminDesk';
import { FrontOffice } from './components/front/FrontOffice';
import { PharmacyDesk } from './components/pharmacy/PharmacyDesk';
import { ImagingDesk } from './components/ImagingDesk';
import { LabDesk } from './components/LabDesk';
import { LoginGate } from './components/LoginGate';
import { type DeskId, desksFor } from './emr/diagnostics';

const DESK_KEY = 'syncra.desk';

function storedDesk(): DeskId | null {
  try {
    return localStorage.getItem(DESK_KEY) as DeskId | null;
  } catch {
    return null;
  }
}

/** Signs in, then opens the desk for the user's role (remembering the last one they chose). */
export function App() {
  const [session, setSession] = useState<Session | null>(() => loadSession());
  const [chosen, setChosen] = useState<DeskId | null>(storedDesk);
  useEffect(() => onSessionChange(setSession), []);
  // Renew the access token shortly before it expires, from the HttpOnly refresh cookie. If that
  // fails (session over, or a pasted development token) the token simply runs out.
  useEffect(() => {
    if (!session) return undefined;
    const timer = window.setTimeout(() => void iam.refresh().catch(() => undefined),
      Math.max(5_000, session.expiresAt - Date.now() - RENEW_BEFORE_MS));
    return () => window.clearTimeout(timer);
  }, [session]);

  if (!session) return <LoginGate />;
  const available = desksFor(session.roles);
  const desk = chosen && available.includes(chosen) ? chosen : available[0];
  const change = (next: DeskId) => {
    setChosen(next);
    try {
      localStorage.setItem(DESK_KEY, next);
    } catch {
      // per-browser convenience only
    }
  };
  switch (desk) {
    case 'lab':
      return <LabDesk session={session} onDeskChange={change} />;
    case 'imaging':
      return <ImagingDesk session={session} onDeskChange={change} />;
    case 'doctor':
      return <DoctorDesk session={session} onDeskChange={change} />;
    case 'front':
      return <FrontOffice session={session} onDeskChange={change} />;
    case 'pharmacy':
      return <PharmacyDesk session={session} onDeskChange={change} />;
    case 'admin':
      return <AdminDesk session={session} onDeskChange={change} />;
    default:
      return <LoginGate />;
  }
}
