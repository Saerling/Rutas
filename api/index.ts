// Vercel: every request goes through here into the Expo server build (API routes and web pages).
const { createRequestHandler } = require("expo-server/adapter/vercel");

module.exports = createRequestHandler({
  build: require("path").join(__dirname, "../dist/server"),
});
