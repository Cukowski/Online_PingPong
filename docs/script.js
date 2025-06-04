// File: script.js

// ─── 1) Constants ───────────────────────────────────────────────────────────────
const GAME_WIDTH    = 800;
const GAME_HEIGHT   = 600;
const PADDLE_WIDTH  = 10;
const PADDLE_HEIGHT = 80;
const BALL_SIZE     = 15;

// ─── 2) Global State ────────────────────────────────────────────────────────────
let ws;
let authenticated = false;
let playerNumber  = null;
let gameState     = null;

// ─── 3) DOM References ─────────────────────────────────────────────────────────
const canvas      = document.getElementById('gameCanvas');
const ctx         = canvas.getContext('2d');
const BTN_READY   = document.getElementById('readyBtn');
const BTN_PAUSE   = document.getElementById('pauseBtn');
const BTN_RESTART = document.getElementById('restartBtn');

// ─── 4) Resize Canvas to Preserve 4:3 Aspect Ratio ──────────────────────────────
function resizeCanvas() {
  const containerWidth = window.innerWidth;
  const containerHeight = window.innerHeight;
  const gameRatio = GAME_WIDTH / GAME_HEIGHT;

  let newWidth, newHeight;

  if (containerWidth / containerHeight > gameRatio) {
    newHeight = containerHeight;
    newWidth  = newHeight * gameRatio;
  } else {
    newWidth  = containerWidth;
    newHeight = newWidth / gameRatio;
  }

  canvas.style.width  = `${Math.floor(newWidth)}px`;
  canvas.style.height = `${Math.floor(newHeight)}px`;
  canvas.width        = Math.floor(newWidth);
  canvas.height       = Math.floor(newHeight);
}

window.addEventListener('resize', resizeCanvas);
resizeCanvas();

// ─── 5) WebSocket Connection Logic ─────────────────────────────────────────────
function connectWebSocket() {
  console.log('Opening WebSocket to wss://pong-online.site/ws/');
  ws = new WebSocket("wss://pong-online.site/ws/");

  ws.onopen = () => {
    console.log(`WebSocket opened. Waiting for server prompt...`);
  };

  ws.onmessage = (evt) => {
    const msg = evt.data.trim();
    console.log('Received WebSocket message:', msg);

    // Phase 1: authentication handshake
    if (!authenticated) {
      if (msg === 'ENTER_SECRET') {
        const secret = prompt('Enter shared secret:');
        if (secret !== null) {
          ws.send(secret);
          console.log('Sent secret to server');
        }
      } else if (msg === 'OK') {
        console.log('Server replied OK. Marking authenticated = true');
        authenticated = true;
        choosePlayerNumber();
      } else if (msg === 'FAIL') {
        alert('Wrong secret—connection closed by server.');
        console.warn('Server replied FAIL. Closing WebSocket.');
        ws.close();
      }
      return;
    }

    // Phase 2: after authentication, parse JSON state
    try {
      const obj = JSON.parse(msg);
      if (obj.type === 'STATE') {
        gameState = obj;
      }
    } catch (e) {
      console.warn('WebSocket message was not JSON:', msg);
    }
  };

  ws.onclose = () => {
    console.warn('WebSocket connection closed. Will retry in 2 seconds.');
    authenticated  = false;
    playerNumber   = null;
    gameState      = null;
    setTimeout(connectWebSocket, 2000);
  };

  ws.onerror = (err) => {
    console.error('WebSocket encountered error:', err);
  };
}

// ─── 6) After “OK”, Pick Player Slot ──────────────────────────────────────────
function choosePlayerNumber() {
  while (true) {
    const pNumStr = prompt('Enter player number (1 or 2):', '1');
    const pNum = parseInt(pNumStr, 10);
    if (pNum === 1 || pNum === 2) {
      playerNumber = pNum;
      ws.send(JSON.stringify({ action: 'CHOOSE_PLAYER', p: playerNumber }));
      console.log(`Sent CHOOSE_PLAYER => ${playerNumber}`);
      break;
    }
    alert('Please enter either 1 or 2.');
  }
}

// ─── 7) Sending MOVE & CONTROL Commands ────────────────────────────────────────
function sendMove(dir) {
  if (ws && authenticated && playerNumber) {
    const payload = JSON.stringify({ type: 'MOVE', dir });
    ws.send(payload);
  }
}

function sendControl(action) {
  if (ws && authenticated && playerNumber) {
    const payload = JSON.stringify({ type: 'CONTROL', action });
    ws.send(payload);
    console.log('Sent CONTROL:', action);
  }
}

// ─── 8) Button Event Listeners ─────────────────────────────────────────────────
BTN_READY.addEventListener('click', () => {
  sendControl('READY');
  BTN_READY.disabled = true;
});
BTN_PAUSE.addEventListener('click', () => {
  if (!gameState) return;
  if (!gameState.paused) {
    sendControl('PAUSE');
    // Change button label to “CONTINUE” or similar if you like:
    BTN_PAUSE.textContent = 'CONTINUE';
  } else {
    sendControl('RESUME');
    BTN_PAUSE.textContent = 'PAUSE';
  }
});
BTN_RESTART.addEventListener('click', () => {
  sendControl('RESTART');
  BTN_READY.disabled = false;
  // Make sure the pause button label resets:
  BTN_PAUSE.textContent = 'PAUSE';
});

// ─── 9A) Keyboard Controls (Desktop) ───────────────────────────────────────────
window.addEventListener('keydown', (e) => {
  if (!authenticated || !gameState) return;
  if (!gameState.ready1 || !gameState.ready2 || gameState.winner !== 0 || gameState.paused) return;
  let dir = 0;
  if (e.key === 'ArrowUp' || e.key.toLowerCase() === 'w')   dir = -1;
  if (e.key === 'ArrowDown' || e.key.toLowerCase() === 's') dir = 1;
  if (dir !== 0) sendMove(dir);
});
window.addEventListener('keyup', (e) => {
  if (!authenticated || !gameState) return;
  const relevant = ['ArrowUp','ArrowDown','w','W','s','S'];
  if (relevant.includes(e.key)) sendMove(0);
});

// ─── 9B) Touch Controls (Mobile) ───────────────────────────────────────────────
let activeTouchId = null;

canvas.addEventListener('touchstart', (e) => {
  if (!authenticated || !gameState) return;
  // Prevent paddle movement if paused or not ready
  if (!gameState.ready1 || !gameState.ready2 || gameState.winner !== 0 || gameState.paused) return;
  e.preventDefault();
  const touch = e.changedTouches[0];
  activeTouchId = touch.identifier;
  handleTouchMove(touch);
}, { passive: false });

canvas.addEventListener('touchmove', (e) => {
  if (activeTouchId === null || !authenticated || !gameState) return;
  for (let t of e.changedTouches) {
    if (t.identifier === activeTouchId) {
      e.preventDefault();
      handleTouchMove(t);
      return;
    }
  }
}, { passive: false });

canvas.addEventListener('touchend', (e) => {
  if (activeTouchId === null) return;
  for (let t of e.changedTouches) {
    if (t.identifier === activeTouchId) {
      e.preventDefault();
      activeTouchId = null;
      sendMove(0);
      return;
    }
  }
}, { passive: false });

canvas.addEventListener('touchcancel', (e) => {
  if (activeTouchId === null) return;
  for (let t of e.changedTouches) {
    if (t.identifier === activeTouchId) {
      e.preventDefault();
      activeTouchId = null;
      sendMove(0);
      return;
    }
  }
}, { passive: false });

function handleTouchMove(touch) {
  const rect = canvas.getBoundingClientRect();
  const y = touch.clientY - rect.top;
  const mid = rect.height / 2;
  let dir = 0;
  if (y < mid - 20)      dir = -1;
  else if (y > mid + 20) dir = 1;
  else                   dir = 0;
  sendMove(dir);
}

// ─── 10) Main Render Loop (~60 FPS) ─────────────────────────────────────────────
function gameLoop() {
  ctx.clearRect(0, 0, canvas.width, canvas.height);

  // (a) Not yet received any state
  if (!gameState) {
    ctx.fillStyle = '#FFF';
    ctx.font      = '30px Arial';
    ctx.textAlign = 'center';
    ctx.fillText('Connecting to server...', canvas.width / 2, canvas.height / 2);
    requestAnimationFrame(gameLoop);
    return;
  }

  // (b) Not both ready
  if (!gameState.ready1 || !gameState.ready2) {
    ctx.fillStyle = '#FFF';
    ctx.font      = '30px Arial';
    ctx.textAlign = 'center';
    ctx.fillText(
      'Waiting for both players to click READY...',
      canvas.width / 2,
      canvas.height / 2
    );
    requestAnimationFrame(gameLoop);
    return;
  }

  // (c) Game over
  if (gameState.winner !== 0) {
    ctx.fillStyle = 'rgba(0,0,0,0.5)';
    ctx.fillRect(0, 0, canvas.width, canvas.height);

    ctx.fillStyle = (gameState.winner === playerNumber) ? '#0F0' : '#F00';
    ctx.font      = '48px Arial';
    ctx.textAlign = 'center';
    const msg = (gameState.winner === playerNumber) ? 'You Win!' : 'You Lose';
    ctx.fillText(msg, canvas.width/2, canvas.height/2 - 20);

    ctx.fillStyle = '#FFF';
    ctx.font      = '32px Arial';
    const scoreMsg = `P1: ${gameState.score1}   P2: ${gameState.score2}`;
    ctx.fillText(scoreMsg, canvas.width/2, canvas.height/2 + 20);

    requestAnimationFrame(gameLoop);
    return;
  }

  // (d) Draw center dashed line
  ctx.strokeStyle = '#555';
  ctx.setLineDash([10, 10]);
  ctx.beginPath();
  ctx.moveTo(canvas.width / 2, 0);
  ctx.lineTo(canvas.width / 2, canvas.height);
  ctx.stroke();
  ctx.setLineDash([]);

  // (e) Scale factors
  const xScale = canvas.width  / GAME_WIDTH;
  const yScale = canvas.height / GAME_HEIGHT;

  // (f) Draw paddles
  ctx.fillStyle = '#FFF';
  const p1y = gameState.p1Y * yScale;
  const p2y = gameState.p2Y * yScale;
  const pw  = PADDLE_WIDTH * xScale;
  const ph  = PADDLE_HEIGHT * yScale;
  ctx.fillRect(0, p1y, pw, ph);
  ctx.fillRect(canvas.width - pw, p2y, pw, ph);

  // (g) Draw ball (only if not paused)
  if (!gameState.paused) {
    const bx = gameState.ballX * xScale;
    const by = gameState.ballY * yScale;
    const bs = BALL_SIZE   * xScale;
    ctx.fillRect(bx, by, bs, bs);
  }

  // (h) Draw scores
  ctx.fillStyle = '#FFF';
  ctx.font      = '32px Arial';
  ctx.textAlign = 'center';
  ctx.fillText(String(gameState.score1), canvas.width/2 - 50, 50);
  ctx.fillText(String(gameState.score2), canvas.width/2 + 50, 50);

  // ─── If the game is paused, overlay the “PAUSE” text ─────────────────────────
  if (gameState.paused) {
    // translucent background
    ctx.fillStyle = 'rgba(0, 0, 0, 0.6)';
    ctx.fillRect(0, 0, canvas.width, canvas.height);

    // big “PAUSE” text
    ctx.fillStyle = '#FFF';
    ctx.font      = 'bold 60px Arial';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText('PAUSE', canvas.width / 2, canvas.height / 2);
  }

  requestAnimationFrame(gameLoop);
}

// ─── 11) Start Everything ───────────────────────────────────────────────────────
requestAnimationFrame(gameLoop);
connectWebSocket();
