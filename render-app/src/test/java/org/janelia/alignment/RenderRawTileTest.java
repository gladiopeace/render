package org.janelia.alignment;

import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

import java.awt.image.BufferedImage;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;

import org.janelia.alignment.loader.ImageJDefaultLoader;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ImageProcessorCache;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests rendering of a raw tile without transformations.
 *
 * @author Eric Trautman
 */
public class RenderRawTileTest {

    @Test
    public void testRender() {

        final ImageAndMask imageWithoutMask = new ImageAndMask("src/test/resources/raw-tile-test/raw-tile.png",
                                                               null);

        final ImageProcessor rawIp = ImageJDefaultLoader.INSTANCE.load(imageWithoutMask.getImageUrl());
        final BufferedImage rawImage =
                ArgbRenderer.targetToARGBImage(new TransformMeshMappingWithMasks.ImageProcessorWithMasks(rawIp,
                                                                                                         null,
                                                                                                         null),
                                               false);

        final ChannelSpec channelSpec = new ChannelSpec();
        channelSpec.putMipmap(0, imageWithoutMask);

        final TileSpec tileSpec = new TileSpec();
        tileSpec.addChannel(channelSpec);

        final RenderParameters tileRenderParameters =
                new RenderParameters(null, 0, 0, rawIp.getWidth(), rawIp.getHeight(), 1.0);

        tileRenderParameters.addTileSpec(tileSpec);
        tileRenderParameters.setSkipInterpolation(true);
        tileRenderParameters.initializeDerivedValues();

        final BufferedImage renderedImage = tileRenderParameters.openTargetImage();

        ArgbRenderer.render(tileRenderParameters,
                            renderedImage,
                            ImageProcessorCache.DISABLED_CACHE);

        Assert.assertEquals("bad rendered image width",
                            rawImage.getWidth(), renderedImage.getWidth());

        Assert.assertEquals("bad rendered image height",
                            rawImage.getHeight(), renderedImage.getHeight());

        for (int x = 0; x < rawImage.getWidth(); x++) {
            for (int y = 0; y < rawImage.getHeight(); y++) {
                Assert.assertEquals("bad rendered pixel at (" + x + ", " + y + ")",
                                    rawImage.getRGB(x, y), renderedImage.getRGB(x, y));
            }
        }
    }
}
