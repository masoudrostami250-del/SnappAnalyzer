# Snapp Voice 17000 + Pickup 1.5km

Separate variant based on the 1km female-voice app.

- Pickup distance <= 1.5 km AND destination fare rate >= 17,000 toman/km: female Persian voice says «برو».
- Pickup distance > 1.5 km OR destination fare rate < 17,000 toman/km: female Persian voice says «نرو».
- Fare rate is calculated using destination distance only; pickup distance is not added.
- Missing/uncertain pickup or destination distances fail safe with «نرو».
- Unique application ID: `com.snapfloat.voice17000pickup15km`, so this variant can be installed alongside earlier variants.
- Voice files are generated in GitHub Actions using Microsoft `fa-IR-DilaraNeural` and packaged for offline playback.

Branch: `voice-17000-pickup-1-5km-female-v1`
