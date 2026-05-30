package app.clothescast.ui.garment

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.clothescast.R

//
// One preview per clothing drawable — each renders the vector at a fixed size
// on a neutral surface so a change to any single `ic_outfit_*.xml` (path edit,
// fill swap, viewport tweak) surfaces as a one-file PNG diff in the PR.
// Existing outfit previews snapshot top+bottom *combinations*; these isolate
// each drawable so reviewers can eyeball the silhouette without surrounding
// chrome.
//

@Composable
private fun ClothingDrawableFrame(@DrawableRes iconRes: Int, label: String) {
    Surface(color = Color(0xFFF5F5F5)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth().padding(24.dp),
        ) {
            Image(
                painter = painterResource(id = iconRes),
                contentDescription = label,
                modifier = Modifier.size(160.dp),
            )
        }
    }
}

@Preview(name = "Clothing · umbrella", widthDp = 360)
@Composable
internal fun ClothingUmbrellaPreview() {
    ClothingDrawableFrame(R.drawable.ic_outfit_umbrella, "umbrella")
}

@Preview(name = "Clothing · sweater + umbrella", widthDp = 360)
@Composable
internal fun ClothingSweaterWithUmbrellaPreview() {
    Surface(color = Color(0xFFF5F5F5)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth().padding(24.dp),
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_outfit_umbrella),
                contentDescription = "umbrella",
                modifier = Modifier.size(160.dp),
            )
            Image(
                painter = painterResource(id = R.drawable.ic_outfit_sweater),
                contentDescription = "sweater",
                modifier = Modifier.size(160.dp),
            )
        }
    }
}

@Preview(name = "Clothing · t-shirt", widthDp = 360)
@Composable
internal fun ClothingTShirtPreview() {
    ClothingDrawableFrame(R.drawable.ic_outfit_tshirt, "t-shirt")
}

@Preview(name = "Clothing · polo", widthDp = 360)
@Composable
internal fun ClothingPoloPreview() {
    ClothingDrawableFrame(R.drawable.ic_outfit_polo, "polo")
}

@Preview(name = "Clothing · sweater", widthDp = 360)
@Composable
internal fun ClothingSweaterPreview() {
    ClothingDrawableFrame(R.drawable.ic_outfit_sweater, "sweater")
}

@Preview(name = "Clothing · thin jacket", widthDp = 360)
@Composable
internal fun ClothingThinJacketPreview() {
    ClothingDrawableFrame(R.drawable.ic_outfit_thin_jacket, "thin jacket")
}

@Preview(name = "Clothing · thick jacket", widthDp = 360)
@Composable
internal fun ClothingThickJacketPreview() {
    ClothingDrawableFrame(R.drawable.ic_outfit_thick_jacket, "thick jacket")
}

@Preview(name = "Clothing · thick coat", widthDp = 360)
@Composable
internal fun ClothingThickCoatPreview() {
    ClothingDrawableFrame(R.drawable.ic_outfit_thick_coat, "thick coat")
}

@Preview(name = "Clothing · puffer jacket", widthDp = 360)
@Composable
internal fun ClothingPufferJacketPreview() {
    ClothingDrawableFrame(R.drawable.ic_outfit_puffer_jacket, "puffer jacket")
}

@Preview(name = "Clothing · shorts", widthDp = 360)
@Composable
internal fun ClothingShortsPreview() {
    ClothingDrawableFrame(R.drawable.ic_outfit_shorts, "shorts")
}

@Preview(name = "Clothing · short skirt", widthDp = 360)
@Composable
internal fun ClothingShortSkirtPreview() {
    ClothingDrawableFrame(R.drawable.ic_outfit_short_skirt, "short skirt")
}

@Preview(name = "Clothing · long skirt", widthDp = 360)
@Composable
internal fun ClothingLongSkirtPreview() {
    ClothingDrawableFrame(R.drawable.ic_outfit_skirt, "long skirt")
}

@Preview(name = "Clothing · jeans", widthDp = 360)
@Composable
internal fun ClothingJeansPreview() {
    ClothingDrawableFrame(R.drawable.ic_outfit_jeans, "jeans")
}

@Preview(name = "Clothing · long pants", widthDp = 360)
@Composable
internal fun ClothingLongPantsPreview() {
    ClothingDrawableFrame(R.drawable.ic_outfit_long_pants, "long pants")
}

@Preview(name = "Clothing · gloves", widthDp = 360)
@Composable
internal fun ClothingGlovesPreview() {
    ClothingDrawableFrame(R.drawable.ic_outfit_gloves, "gloves")
}

@Preview(name = "Clothing · thick jacket + gloves", widthDp = 360)
@Composable
internal fun ClothingThickJacketWithGlovesPreview() {
    Surface(color = Color(0xFFF5F5F5)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth().padding(24.dp),
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_outfit_thick_jacket),
                contentDescription = "thick jacket",
                modifier = Modifier.size(160.dp),
            )
            Image(
                painter = painterResource(id = R.drawable.ic_outfit_gloves),
                contentDescription = "gloves",
                modifier = Modifier.size(160.dp),
            )
        }
    }
}
