if ('serviceWorker' in navigator) { navigator.serviceWorker.getRegistrations().then(rs => rs.forEach(r => { if (r.scope.includes('/hwp-studio/')) r.unregister() })) }
