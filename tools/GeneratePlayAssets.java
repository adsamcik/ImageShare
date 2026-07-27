import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Generates Android and Google Play icon assets from the image-generated master mark. */
public final class GeneratePlayAssets {
    private static final Path MASTER_PATH =
        Path.of("docs", "store-assets", "source", "imageshare-icon-master.png");
    private static final Path STORE_OUTPUT = Path.of("docs", "store-assets");
    private static final Path FASTLANE_IMAGES =
        Path.of("fastlane", "metadata", "android", "en-US", "images");
    private static final Path SCREENSHOT_OUTPUT = FASTLANE_IMAGES.resolve("phoneScreenshots");
    private static final Path RESOURCES = Path.of("app", "src", "main", "res");

    private static final Color ICE = Color.decode("#E7F9FF");
    private static final Color SKY = Color.decode("#BDEEFF");
    private static final Color NAVY = Color.decode("#071A3D");
    private static final Color SLATE = Color.decode("#334155");
    private static final Density[] DENSITIES = {
        new Density("mdpi", 1),
        new Density("hdpi", 1.5),
        new Density("xhdpi", 2),
        new Density("xxhdpi", 3),
        new Density("xxxhdpi", 4)
    };

    private static BufferedImage master;
    private static Rectangle markBounds;

    private GeneratePlayAssets() {}

    public static void main(String[] args) throws IOException {
        master = ImageIO.read(MASTER_PATH.toFile());
        if (master == null || !master.getColorModel().hasAlpha()) {
            throw new IOException("Transparent PNG master not found at " + MASTER_PATH);
        }
        markBounds = findOpaqueBounds(master);

        Files.createDirectories(STORE_OUTPUT);
        Files.createDirectories(FASTLANE_IMAGES);
        Files.createDirectories(SCREENSHOT_OUTPUT);

        makeAndroidIcons();
        makeStoreIcon();
        makeFeatureGraphic();
        preparePhoneScreenshot("01-home-light.png");
        preparePhoneScreenshot("02-picker-dark.png");
        System.out.println("Generated Android and Google Play artwork from " + MASTER_PATH);
    }

    private static void makeAndroidIcons() throws IOException {
        for (Density density : DENSITIES) {
            int launcherSize = pixels(108, density.scale());
            Path mipmap = RESOURCES.resolve("mipmap-" + density.name());
            Files.createDirectories(mipmap);

            BufferedImage foreground = transparentImage(launcherSize, launcherSize);
            drawMasterCentered(foreground, pixels(64, density.scale()), pixels(64, density.scale()));
            savePng(foreground, mipmap.resolve("ic_launcher_foreground.png"));

            BufferedImage monochrome = copy(foreground);
            makeWhiteSilhouette(monochrome);
            savePng(monochrome, mipmap.resolve("ic_launcher_monochrome.png"));

            int notificationSize = pixels(24, density.scale());
            Path drawable = RESOURCES.resolve("drawable-" + density.name());
            Files.createDirectories(drawable);
            BufferedImage notification = transparentImage(notificationSize, notificationSize);
            drawMasterCentered(
                notification,
                pixels(21, density.scale()),
                pixels(19, density.scale())
            );
            makeWhiteSilhouette(notification);
            savePng(notification, drawable.resolve("ic_notification.png"));
        }
    }

    private static void makeStoreIcon() throws IOException {
        BufferedImage icon = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = graphics(icon);
        graphics.setPaint(new GradientPaint(0, 0, Color.WHITE, 512, 512, SKY));
        graphics.fillRect(0, 0, 512, 512);
        graphics.dispose();
        drawMasterCentered(icon, 392, 342);
        savePng(icon, STORE_OUTPUT.resolve("icon-512.png"));
        savePng(icon, FASTLANE_IMAGES.resolve("icon.png"));
    }

    private static void makeFeatureGraphic() throws IOException {
        BufferedImage image = new BufferedImage(1024, 500, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = graphics(image);
        graphics.setPaint(new GradientPaint(0, 0, Color.WHITE, 1024, 500, ICE));
        graphics.fillRect(0, 0, 1024, 500);

        graphics.setColor(new Color(255, 255, 255, 205));
        graphics.fill(new RoundRectangle2D.Double(60, 60, 360, 380, 72, 72));
        graphics.dispose();
        drawMaster(image, new Rectangle(101, 115, 280, 270));

        graphics = graphics(image);
        graphics.setColor(NAVY);
        graphics.setFont(new Font("SansSerif", Font.BOLD, 70));
        graphics.drawString("ImageShare", 468, 190);
        graphics.setColor(Color.decode("#087EA4"));
        graphics.setFont(new Font("SansSerif", Font.PLAIN, 35));
        graphics.drawString("Resize privately. Share faster.", 470, 266);
        graphics.setColor(SLATE);
        graphics.setFont(new Font("SansSerif", Font.PLAIN, 23));
        graphics.drawString("On-device image prep  \u2022  No ads  \u2022  No tracking", 472, 326);
        graphics.dispose();

        savePng(image, STORE_OUTPUT.resolve("feature-graphic-1024x500.png"));
        savePng(image, FASTLANE_IMAGES.resolve("featureGraphic.png"));
    }

    private static void preparePhoneScreenshot(String name) throws IOException {
        Path sourcePath = Path.of("docs", "screenshots", "phone", name);
        if (!Files.exists(sourcePath)) {
            return;
        }
        BufferedImage source = ImageIO.read(sourcePath.toFile());
        int targetHeight = Math.min(source.getHeight(), source.getWidth() * 2);
        int top = (source.getHeight() - targetHeight) / 2;
        BufferedImage cropped =
            new BufferedImage(source.getWidth(), targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = graphics(cropped);
        graphics.drawImage(source, 0, -top, null);
        graphics.dispose();
        savePng(cropped, SCREENSHOT_OUTPUT.resolve(name));
    }

    private static void drawMasterCentered(
        BufferedImage destination,
        int maxWidth,
        int maxHeight
    ) {
        double scale = Math.min(
            maxWidth / (double) markBounds.width,
            maxHeight / (double) markBounds.height
        );
        int width = (int) Math.round(markBounds.width * scale);
        int height = (int) Math.round(markBounds.height * scale);
        drawMaster(
            destination,
            new Rectangle(
                (destination.getWidth() - width) / 2,
                (destination.getHeight() - height) / 2,
                width,
                height
            )
        );
    }

    private static void drawMaster(BufferedImage destination, Rectangle target) {
        Graphics2D graphics = graphics(destination);
        graphics.drawImage(
            master,
            target.x,
            target.y,
            target.x + target.width,
            target.y + target.height,
            markBounds.x,
            markBounds.y,
            markBounds.x + markBounds.width,
            markBounds.y + markBounds.height,
            null
        );
        graphics.dispose();
    }

    private static Rectangle findOpaqueBounds(BufferedImage image) throws IOException {
        int left = image.getWidth();
        int top = image.getHeight();
        int right = -1;
        int bottom = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int alpha = (image.getRGB(x, y) >>> 24) & 0xff;
                if (alpha >= 32) {
                    left = Math.min(left, x);
                    top = Math.min(top, y);
                    right = Math.max(right, x);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        if (right < left || bottom < top) {
            throw new IOException("The icon master contains no opaque artwork");
        }
        return new Rectangle(left, top, right - left + 1, bottom - top + 1);
    }

    private static BufferedImage transparentImage(int width, int height) {
        return new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    }

    private static BufferedImage copy(BufferedImage source) {
        BufferedImage destination =
            new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = destination.createGraphics();
        graphics.drawImage(source, 0, 0, null);
        graphics.dispose();
        return destination;
    }

    private static void makeWhiteSilhouette(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int alpha = (image.getRGB(x, y) >>> 24) & 0xff;
                image.setRGB(x, y, (alpha << 24) | 0x00ffffff);
            }
        }
    }

    private static Graphics2D graphics(BufferedImage image) {
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(
            RenderingHints.KEY_ANTIALIASING,
            RenderingHints.VALUE_ANTIALIAS_ON
        );
        graphics.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BICUBIC
        );
        graphics.setRenderingHint(
            RenderingHints.KEY_RENDERING,
            RenderingHints.VALUE_RENDER_QUALITY
        );
        graphics.setRenderingHint(
            RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON
        );
        return graphics;
    }

    private static void savePng(BufferedImage image, Path output) throws IOException {
        Files.createDirectories(output.getParent());
        ImageIO.write(image, "png", output.toFile());
    }

    private static int pixels(int dp, double scale) {
        return (int) Math.round(dp * scale);
    }

    private record Density(String name, double scale) {}
}
