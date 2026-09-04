(function () {
  const status = document.getElementById("status");
  const coords = document.getElementById("coords");

  function setStatus(text) {
    status.textContent = text;
  }

  function onPosition(position) {
    const { latitude, longitude, accuracy } = position.coords;
    setStatus("GPS live");
    coords.textContent =
      latitude.toFixed(6) + ", " + longitude.toFixed(6) +
      "  (±" + Math.round(accuracy) + " m)";
  }

  function onError(error) {
    setStatus("GPS error: " + (error && error.message ? error.message : "denied"));
  }

  if (!navigator.geolocation) {
    setStatus("Geolocation is not available in this WebView");
    return;
  }

  navigator.geolocation.watchPosition(onPosition, onError, {
    enableHighAccuracy: true,
    maximumAge: 1000,
    timeout: 15000
  });
})();
