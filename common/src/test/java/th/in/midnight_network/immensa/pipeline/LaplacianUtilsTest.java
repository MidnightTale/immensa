package th.in.midnight_network.immensa.pipeline;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class LaplacianUtilsTest {
    @Test
    void flatCropMatchesFullAlignCornersFalseResize() {
        int sourceH = 9;
        int sourceW = 11;
        int destinationH = 36;
        int destinationW = 44;
        float[] flat = new float[sourceH * sourceW];
        Random random = new Random(812731L);
        for (int i = 0; i < flat.length; i++) flat[i] = random.nextFloat() * 2000.0f - 500.0f;

        float[][] source = new float[sourceH][sourceW];
        for (int row = 0; row < sourceH; row++) {
            System.arraycopy(flat, row * sourceW, source[row], 0, sourceW);
        }
        float[][] complete = LaplacianUtils.bilinearResize(source, destinationH, destinationW);

        int cropRow = 7;
        int cropColumn = 9;
        int cropH = 21;
        int cropW = 25;
        float[] expected = new float[cropH * cropW];
        for (int row = 0; row < cropH; row++) {
            System.arraycopy(complete[cropRow + row], cropColumn,
                    expected, row * cropW, cropW);
        }
        float[] actual = LaplacianUtils.bilinearResizeCrop(
                flat, 0, sourceH, sourceW, destinationH, destinationW,
                cropRow, cropColumn, cropH, cropW);

        assertArrayEquals(expected, actual, 0.0002f);
    }

    @Test
    void flatCropSupportsChannelOffsetsAndClampedEdges() {
        int sourceH = 3;
        int sourceW = 4;
        int channelSize = sourceH * sourceW;
        float[] channels = new float[channelSize * 2];
        for (int i = 0; i < channelSize; i++) {
            channels[i] = -1000.0f;
            channels[channelSize + i] = i;
        }

        float[] actual = LaplacianUtils.bilinearResizeCrop(
                channels, channelSize, sourceH, sourceW, 6, 8,
                -1, -2, 8, 12);
        float[][] source = {
                {0, 1, 2, 3},
                {4, 5, 6, 7},
                {8, 9, 10, 11}
        };
        float[][] complete = LaplacianUtils.bilinearResize(source, 6, 8);
        float[] expected = new float[8 * 12];
        for (int row = 0; row < 8; row++) for (int column = 0; column < 12; column++) {
            int clampedRow = Math.max(0, Math.min(5, row - 1));
            int clampedColumn = Math.max(0, Math.min(7, column - 2));
            expected[row * 12 + column] = complete[clampedRow][clampedColumn];
        }
        assertArrayEquals(expected, actual, 0.00001f);
    }
}
