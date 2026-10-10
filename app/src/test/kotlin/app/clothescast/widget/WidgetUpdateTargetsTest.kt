package app.clothescast.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Guards how [updateAllClothesCastWidgets] finds each widget kind's IDs: by its
 * own receiver component, never by Glance's saved class-name map, which a
 * release build's renamed classes can leave pointing one kind at another.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class WidgetUpdateTargetsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `each kind's widget is the one its receiver hosts`() {
        CLOTHESCAST_WIDGET_KINDS.forEach { kind ->
            val receiver: GlanceAppWidgetReceiver = kind.receiver.getDeclaredConstructor().newInstance()
            assertEquals(kind.label, receiver.glanceAppWidget.javaClass, kind.widget().javaClass)
        }
    }

    @Test
    fun `every manifest widget receiver is covered once`() {
        assertEquals(
            setOf(
                OutfitWidgetReceiver::class.java,
                FeelsLikeWidgetReceiver::class.java,
                SevenDayFeelsLikeWidgetReceiver::class.java,
                ChanceOfRainWidgetReceiver::class.java,
                ConditionsWidgetReceiver::class.java,
            ),
            CLOTHESCAST_WIDGET_KINDS.map { it.receiver }.toSet(),
        )
        assertEquals(5, CLOTHESCAST_WIDGET_KINDS.size)
    }

    @Test
    fun `ids come from each kind's own receiver`() {
        val manager = AppWidgetManager.getInstance(context)
        shadowOf(manager).setAllowedToBindAppWidgets(true)
        manager.bindAppWidgetIdIfAllowed(16, ComponentName(context, OutfitWidgetReceiver::class.java))
        manager.bindAppWidgetIdIfAllowed(22, ComponentName(context, FeelsLikeWidgetReceiver::class.java))
        manager.bindAppWidgetIdIfAllowed(25, ComponentName(context, ChanceOfRainWidgetReceiver::class.java))

        val idsByLabel = CLOTHESCAST_WIDGET_KINDS.associate { it.label to placedWidgetIds(context, it).toList() }

        assertEquals(listOf(16), idsByLabel["Outfit"])
        assertEquals(listOf(22), idsByLabel["Feels-like"])
        assertEquals(listOf(25), idsByLabel["Chance of rain"])
        assertArrayEquals(IntArray(0), placedWidgetIds(context, CLOTHESCAST_WIDGET_KINDS.first { it.label == "Conditions" }))
    }
}
