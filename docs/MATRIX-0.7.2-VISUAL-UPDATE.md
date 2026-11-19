# MATRIX 0.7.2 visual update

- Preserves the existing translucent glass cards and green/gold accents.
- Reduces primary mode controls to 64dp and utility/navigation controls to 44dp, with smaller text and horizontal insets.
- Places a continuously rotating green wireframe globe behind the translucent screen.
- Animates a luminous green node around the globe with a long fading route trail.
- Adds a small top-right RX/TX rate panel in gold, reading Android traffic counters for the MATRIX app UID only.

The globe and route animation are decorative local Canvas rendering; they do not show the user's real location or a live network route. The traffic panel displays app UID byte rates and should not be interpreted as VPN throughput.
