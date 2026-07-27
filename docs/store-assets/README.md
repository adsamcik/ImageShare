# ImageShare icon artwork

The transparent source of truth is `source/imageshare-icon-master.png`, created with OpenAI image generation and cleaned to a transparent production master. The generator derives:

- adaptive launcher foregrounds for every Android density;
- monochrome launcher layers for Android themed icons;
- white-alpha notification icons for every Android density;
- `icon-512.png` and Fastlane `images/icon.png` for Google Play;
- `feature-graphic-1024x500.png` and Fastlane `images/featureGraphic.png`.

Regenerate all derived assets and the cropped Fastlane phone screenshots from the repository root with the JDK used by Gradle:

```powershell
& "$env:JAVA_HOME\\bin\\java.exe" .\\tools\\GeneratePlayAssets.java
```

Do not edit the density-specific PNGs by hand. Replace the transparent master and rerun the generator instead. Visually inspect the launcher mask variants, notification glyph, and Play artwork before every store upload.
