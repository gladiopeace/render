package org.janelia.alignment.match;

import org.janelia.alignment.match.parameters.FeatureRenderClipParameters;
import org.janelia.alignment.match.parameters.FeatureRenderParameters;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link CanvasRenderParametersUrlTemplate} class.
 *
 * @author Eric Trautman
 */
public class CanvasRenderParametersUrlTemplateTest {

    private String tileId = "aaa";

    @Test
    public void testLoad() throws Exception {

        final RenderableCanvasIdPairs renderableCanvasIdPairs =
                RenderableCanvasIdPairs.load("src/test/resources/match-test/tile_pairs_v12_acquire_merged_1_5.json");

        Assert.assertNotNull("pairs not loaded", renderableCanvasIdPairs);

        Assert.assertEquals("incorrect number of pairs loaded", 4722, renderableCanvasIdPairs.size());

        final String baseDataUrl = "http://render/render-ws/v1";
        final FeatureRenderParameters featureRenderParameters = new FeatureRenderParameters();
        featureRenderParameters.renderWithFilter = false;
        featureRenderParameters.renderWithoutMask = false;
        final FeatureRenderClipParameters clipParameters = new FeatureRenderClipParameters();
        CanvasRenderParametersUrlTemplate templateForRun =
                CanvasRenderParametersUrlTemplate.getTemplateForRun(
                        renderableCanvasIdPairs.getRenderParametersUrlTemplate(baseDataUrl),
                        featureRenderParameters,
                        clipParameters);
        Assert.assertEquals("invalid template derived for basic run",
                            baseDataUrl + "/owner/flyTEM/project/FAFB00/stack/v12_acquire_merged/tile/{id}/render-parameters?" +
                            "scale=1.0&normalizeForMatching=true",
                            templateForRun.getTemplateString());

        featureRenderParameters.renderFullScaleWidth = 2760;
        featureRenderParameters.renderFullScaleHeight = 2330;
        featureRenderParameters.renderScale = 0.8;
        featureRenderParameters.renderWithFilter = true;
        featureRenderParameters.renderFilterListName = "fav";
        featureRenderParameters.renderWithoutMask = true;

        templateForRun =
                CanvasRenderParametersUrlTemplate.getTemplateForRun(
                        renderableCanvasIdPairs.getRenderParametersUrlTemplate(baseDataUrl),
                        featureRenderParameters,
                        clipParameters);
        Assert.assertEquals("invalid template derived for scaled run",
                            baseDataUrl + "/owner/flyTEM/project/FAFB00/stack/v12_acquire_merged/tile/{id}/render-parameters?" +
                            "width=2760&height=2330&scale=0.8&filter=true&filterListName=fav&excludeMask=true&normalizeForMatching=true",
                            templateForRun.getTemplateString());
    }

    @Test
    public void testGetRenderParametersUrl() {


        testTemplate("http://render:8080/render-ws/v1/tile/{id}/render-parameters",
                     "http://render:8080/render-ws/v1/tile/aaa/render-parameters");

        testTemplate("http://render:8080/render-ws/v1/z/{groupId}/render-parameters",
                     "http://render:8080/render-ws/v1/z/99.0/render-parameters");

        testTemplate("http://render:8080/render-ws/v1/z/{groupId}/tile/{id}/render-parameters",
                     "http://render:8080/render-ws/v1/z/99.0/tile/aaa/render-parameters");

        tileId = "z_1.0_box_12769_7558_13654_18227_0.1";
        testTemplate("http://render:8080/render-ws/v1/z/{groupId}/box/{id}/render-parameters",
                     "http://render:8080/render-ws/v1/z/99.0/box/12769,7558,13654,18227,0.1/render-parameters");
    }

    private void testTemplate(final String templateString,
                              final String expectedResult) {

        final CanvasRenderParametersUrlTemplate template =
                new CanvasRenderParametersUrlTemplate(templateString,
                                                      new FeatureRenderParameters(),
                                                      new FeatureRenderClipParameters());
        final CanvasId canvasId = new CanvasId("99.0", tileId);
        Assert.assertEquals("failed to parse template " + template,
                            expectedResult,
                            template.getRenderParametersUrl(canvasId));
    }

}