// Production entry point, integration-owned. Configure the host first, then load the game: the build bundles this file,
// host.js and main.js into ONE module graph, so the configureHost call below and the game share the same adapter.
// The Super App bridge contract (D09) is not available here, so nothing is wired: the game shows G11.AUTH_ERROR until
// the integration replaces the empty object with the real bridge (getToken, refreshToken, deviceId, haptic, …).
import { configureHost } from './host.js';

configureHost({});
import('./main.js');                     // dynamic: main.js must evaluate after configureHost (static imports run first)
