/** Keep the UI immediate while suppressing the notification pair on a quick handoff. */
export function connectionNotifications(notify: (body: string) => void) {
  let active = false;
  let pending: ReturnType<typeof setTimeout> | null = null;

  function clearPending() {
    if (pending !== null) clearTimeout(pending);
    pending = null;
  }

  return {
    connected(body: string) {
      const reconnected = pending !== null;
      clearPending();
      const duplicate = active;
      active = true;
      if (!reconnected && !duplicate) notify(body);
    },
    disconnected(body: string) {
      if (!active) return;
      active = false;
      clearPending();
      pending = setTimeout(() => {
        pending = null;
        if (!active) notify(body);
      }, 3000);
    },
    reset() {
      clearPending();
      active = false;
    },
    /** Window reloads already have a connected session, not a new connection. */
    restore(connected: boolean) {
      clearPending();
      active = connected;
    },
  };
}
