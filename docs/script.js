// File: script.js

// Constants matching server’s GAME_WIDTH, GAME_HEIGHT, etc.
const GAME_WIDTH    = 800;
const GAME_HEIGHT   = 600;
const PADDLE_WIDTH  = 10;
const PADDLE_HEIGHT = 80;
const BALL_SIZE     = 15;

let ws;
let authenticated = false;
let playerNumber  = null;
let gameState     = null;

// Grab DOM elements
const canvas      = document.getElementById('gameCanvas');
const ctx         = canvas.getContext('2d');
const BTN_READY   = document.getElementById('readyBtn');
const BTN_PAUSE   = document.getElementById('pauseBtn');
const BTN_RESTART = document.getElementById('restartBtn');

// Resize canvas whenever window resizes
function resizeCanvas() {
  canvas.width  = canvas.clientWidth;
  canvas.height = canvas.clientHeight;
}
window.addEventListener('resize', resizeCanvas);
resizeCanvas();

// 1) Prompt for server IP/hostname and WebSocket port, then connect
function connectWebSocket() {
  const host = prompt('Enter server hostname or IP:', '');
  if (!host) {
    alert('Server hostname/IP is required.');
    return;
  }

  let port = prompt('Enter WebSocket port (default: 8080):', '8080');
  if (!port) port = '8080';

  // Use wss:// if the page is loaded over HTTPS, otherwise ws://
  const protocol = (window.location.protocol === 'https:') ? 'wss://' : 'ws://';
  const wsUrl = `${protocol}${host}:${port}`;
  console.log(`→ Opening WebSocket to ${wsUrl}`);
  ws = new WebSocket(wsUrl);

  ws.onopen = () => {
    console.log(`WebSocket opened to ${wsUrl}, waiting for server prompt...`);
  };

  ws.onmessage = (evt) => {
    const msg = evt.data.trim();

    // Phase 1: Authentication handshake
    if (!authenticated) {
      if (msg === 'ENTER_SECRET') {
        const secret = prompt('Enter shared secret:');
        ws.send(secret); // no trailing newline
      }
      else if (msg === 'OK') {
        authenticated = true;
        choosePlayerNumber();
      }
      else if (msg === 'FAIL') {
        alert('Wrong secret—connection closed by server.');
        ws.close();
      }
      return;
    }

    // Phase 2: Already authenticated => treat incoming as JSON "STATE"
    try {
      const obj = JSON.parse(msg);
      if (obj.type === 'STATE') {
        // Store the full gameState (including ready1/ready2)
        gameState = obj;
      }
    } catch (e) {
      console.warn('Invalid JSON from server:', msg);
    }
  };

  ws.onclose = () => {
    console.log('WebSocket closed; will reconnect in 2s...');
    authenticated  = false;
    playerNumber   = null;
    gameState      = null;
    setTimeout(connectWebSocket, 2000);
  };

  ws.onerror = (err) => {
    console.error('WebSocket error:', err);
  };
}

// 2) After secret OK, prompt for player number (1 or 2) and send { action:"CHOOSE_PLAYER", p:… }
function choosePlayerNumber() {
  while (true) {
    const pNumStr = prompt('Enter player number (1 or 2):', '1');
    const pNum = parseInt(pNumStr, 10);
    if (pNum === 1 || pNum === 2) {
      playerNumber = pNum;
      // Immediately tell the server which slot we want:
      ws.send(JSON.stringify({ action: 'CHOOSE_PLAYER', p: playerNumber }));
      break;
    }
    alert('Please enter either 1 or 2.');
  }
}

// 3) Send a MOVE command over WebSocket: { type:"MOVE", dir:-1/0/1 }
function sendMove(dir) {
  if (ws && authenticated && playerNumber) {
    const payload = JSON.stringify({ type: 'MOVE', dir });
    ws.send(payload);
  }
}

// 4) Send a CONTROL command over WebSocket: { type:"CONTROL", action:"READY"/"PAUSE"/"RESUME"/"RESTART" }
function sendControl(action) {
  if (ws && authenticated && playerNumber) {
    const payload = JSON.stringify({ type: 'CONTROL', action });
    ws.send(payload);
  }
}

// Hook up the three buttons:
BTN_READY.addEventListener('click', () => {
  sendControl('READY');
  BTN_READY.disabled = true; // disable until next match
});
BTN_PAUSE.addEventListener('click', () => {
  if (!gameState) return;
  if (!gameState.paused) sendControl('PAUSE');
  else                  sendControl('RESUME');
});
BTN_RESTART.addEventListener('click', () => {
  sendControl('RESTART');
  BTN_READY.disabled = false;
});

// Keyboard controls for paddle (W/S or Up/Down)
window.addEventListener('keydown', (e) => {
  if (!authenticated || !gameState) return;
  // Only allow movement once both players are ready and game is not over
  if (!gameState.ready1 || !gameState.ready2 || gameState.winner !== 0 || gameState.paused) return;
  let dir = 0;
  if (e.key === 'ArrowUp' || e.key.toLowerCase() === 'w') dir = -1;
  if (e.key === 'ArrowDown' || e.key.toLowerCase() === 's') dir = 1;
  if (dir !== 0) sendMove(dir);
});
window.addEventListener('keyup', (e) => {
  if (!authenticated || !gameState) return;
  const relevant = ['ArrowUp','ArrowDown','w','W','s','S'];
  if (relevant.includes(e.key)) sendMove(0);
});

// 5) Main render loop (~60 FPS)
function gameLoop() {
  ctx.clearRect(0, 0, canvas.width, canvas.height);

  // If we have not yet received any gameState => show "Connecting..."
  if (!gameState) {
    ctx.fillStyle = '#FFF';
    ctx.font      = '30px Arial';
    ctx.textAlign = 'center';
    ctx.fillText('Connecting to server...', canvas.width / 2, canvas.height / 2);
    requestAnimationFrame(gameLoop);
    return;
  }

  // If either player is not yet ready => show waiting message
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

  // If game over => show overlay
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

  // Draw dashed center line
  ctx.strokeStyle = '#555';
  ctx.setLineDash([10, 10]);
  ctx.beginPath();
  ctx.moveTo(canvas.width / 2, 0);
  ctx.lineTo(canvas.width / 2, canvas.height);
  ctx.stroke();
  ctx.setLineDash([]);

  // Scale factors (server’s logical size is 800×600)
  const xScale = canvas.width  / GAME_WIDTH;
  const yScale = canvas.height / GAME_HEIGHT;

  // Draw paddles
  ctx.fillStyle = '#FFF';
  const p1y = gameState.p1Y * yScale;
  const p2y = gameState.p2Y * yScale;
  const pw  = PADDLE_WIDTH * xScale;
  const ph  = PADDLE_HEIGHT * yScale;
  ctx.fillRect(0, p1y, pw, ph);
  ctx.fillRect(canvas.width - pw, p2y, pw, ph);

  // Draw ball (unless paused)
  if (!gameState.paused) {
    const bx = gameState.ballX * xScale;
    const by = gameState.ballY * yScale;
    const bs = BALL_SIZE   * xScale;
    ctx.fillRect(bx, by, bs, bs);
  }

  // Draw scores
  ctx.fillStyle = '#FFF';
  ctx.font      = '32px Arial';
  ctx.textAlign = 'center';
  ctx.fillText(String(gameState.score1), canvas.width/2 - 50, 50);
  ctx.fillText(String(gameState.score2), canvas.width/2 + 50, 50);

  requestAnimationFrame(gameLoop);
}

requestAnimationFrame(gameLoop);
connectWebSocket();
