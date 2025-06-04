package gui

import javafx.animation.Animation
import javafx.animation.AnimationTimer
import javafx.animation.KeyFrame
import javafx.animation.KeyValue
import javafx.animation.Timeline
import javafx.beans.property.SimpleDoubleProperty
import javafx.geometry.VPos
import javafx.scene.canvas.Canvas
import javafx.scene.canvas.GraphicsContext
import javafx.scene.input.MouseEvent
import javafx.scene.paint.Color
import javafx.scene.text.Font
import javafx.scene.text.FontWeight
import javafx.scene.text.TextAlignment
import javafx.util.Duration
import model.Vehicle
import kotlin.math.log10
import kotlin.math.max // Для coerceAtLeast
import kotlin.math.min // Для coerceAtMost

data class MapObject(
    val vehicle: Vehicle,
    var currentX: Double,
    var currentY: Double,
    var currentSize: Double,
    var color: Color,
    var alpha: Double = 1.0,
    var activeAnimation: Animation? = null
)

class MapVisualizationManager(
    private val mapCanvas: Canvas,
    private val onObjectClick: (Vehicle) -> Unit
) {
    private val gc: GraphicsContext = mapCanvas.graphicsContext2D
    private val displayedObjects = mutableListOf<MapObject>()
    private val userIdToColorMap = mutableMapOf<Int, Color>()
    private val colors = listOf(
        Color.RED, Color.BLUE, Color.GREEN, Color.ORANGE, Color.PURPLE,
        Color.CYAN, Color.MAGENTA, Color.YELLOWGREEN, Color.SLATEBLUE, Color.TOMATO
    )
    private var colorIndex = 0

    private val minObjectSize = 10.0
    private val maxObjectSize = 40.0 // Уменьшил максимальный, чтобы лучше видеть разницу
    private val defaultObjectSize = 20.0 // Размер по умолчанию, если enginePower невалиден

    // Максимальные координаты из ТЗ для Vehicle.coordinates
    private val worldWidth = 806.0
    private val worldHeight = 922.0

    private var animationLoop: AnimationTimer? = null
    private val activeAnimatedObjects = mutableSetOf<MapObject>()

    init {
        mapCanvas.addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
            findClickedObject(event.x, event.y)?.let { onObjectClick(it) }
        }
    }

    private fun startAnimationLoopIfNeeded() {
        if (animationLoop == null && activeAnimatedObjects.isNotEmpty()) {
            animationLoop = object : AnimationTimer() {
                override fun handle(now: Long) {
                    redrawAll()
                    if (activeAnimatedObjects.isEmpty() && displayedObjects.none { it.activeAnimation?.status == Animation.Status.RUNNING }) {
                        this.stop()
                        animationLoop = null
                        redrawAll()
                    }
                }
            }
            animationLoop?.start()
        }
    }

    private fun getUserColor(userId: Int): Color {
        return userIdToColorMap.computeIfAbsent(userId) {
            val color = colors[colorIndex % colors.size]
            colorIndex++
            color
        }
    }

    private fun calculateObjectSize(enginePower: Double?): Double {
        if (enginePower == null || enginePower <= 0) return defaultObjectSize
        // Примерная логарифмическая шкала, адаптируй под свои данные
        // Предположим, enginePower от 1 до 10000
        val logPower = log10(enginePower.coerceAtLeast(1.0)) // от 0 (для 1) до 4 (для 10000)
        val maxLogPower = 4.0 // log10(10000)
        val scaleFactor = (logPower / maxLogPower).coerceIn(0.0, 1.0)
        return minObjectSize + (maxObjectSize - minObjectSize) * scaleFactor
    }

    private fun scaleX(worldX: Int): Double {
        if (mapCanvas.width == 0.0) return worldX.toDouble()
        // Гарантируем, что объект не выходит за левый/правый край Canvas после масштабирования
        // с учетом его максимального возможного радиуса (maxObjectSize / 2)
        val scaled = (worldX.toDouble() / worldWidth) * mapCanvas.width
        return scaled.coerceIn(maxObjectSize / 2, mapCanvas.width - maxObjectSize / 2)
    }

    private fun scaleY(worldY: Float): Double {
        if (mapCanvas.height == 0.0) return worldY.toDouble()
        val scaled = (worldY.toDouble() / worldHeight) * mapCanvas.height
        return scaled.coerceIn(maxObjectSize / 2, mapCanvas.height - maxObjectSize / 2)
    }

    fun replaceAllVehicles(vehicles: List<Vehicle>) {
        activeAnimatedObjects.forEach { it.activeAnimation?.stop() }
        activeAnimatedObjects.clear()
        displayedObjects.forEach { it.activeAnimation?.stop() }
        displayedObjects.clear()

        vehicles.forEach { vehicle ->
            val color = getUserColor(vehicle.userId)
            val targetSize = calculateObjectSize(vehicle.enginePower)
            val canvasX = scaleX(vehicle.coordinates.x)
            val canvasY = scaleY(vehicle.coordinates.y)
            displayedObjects.add(
                MapObject(vehicle, canvasX, canvasY, targetSize, color, 1.0)
            )
        }
        redrawAll()
    }

    fun addVehicleAnimated(vehicle: Vehicle) {
        if (displayedObjects.any { it.vehicle.id == vehicle.id }) {
            updateVehicleAnimated(vehicle); return
        }
        val color = getUserColor(vehicle.userId)
        val targetSize = calculateObjectSize(vehicle.enginePower)
        val canvasX = scaleX(vehicle.coordinates.x)
        val canvasY = scaleY(vehicle.coordinates.y)

        val mapObject = MapObject(vehicle, canvasX, canvasY, 0.0, color, 0.0)
        displayedObjects.add(mapObject)
        activeAnimatedObjects.add(mapObject)

        val duration = Duration.millis(500.0)
        val alphaProp = SimpleDoubleProperty(0.0).apply { addListener { _, _, nv -> mapObject.alpha = nv.toDouble() } }
        val sizeProp = SimpleDoubleProperty(0.0).apply { addListener { _, _, nv -> mapObject.currentSize = nv.toDouble() } }

        val timeline = Timeline(
            KeyFrame(Duration.ZERO, KeyValue(alphaProp, 0.0), KeyValue(sizeProp, 0.0)),
            KeyFrame(duration, KeyValue(alphaProp, 1.0), KeyValue(sizeProp, targetSize))
        )
        mapObject.activeAnimation = timeline
        timeline.setOnFinished {
            mapObject.activeAnimation = null; mapObject.alpha = 1.0; mapObject.currentSize = targetSize
            activeAnimatedObjects.remove(mapObject)
        }
        timeline.play()
        startAnimationLoopIfNeeded()
    }

    fun removeVehicleAnimated(vehicleId: Int) {
        val mapObject = displayedObjects.find { it.vehicle.id == vehicleId } ?: return
        mapObject.activeAnimation?.stop()
        activeAnimatedObjects.add(mapObject)

        val duration = Duration.millis(500.0)
        val alphaProp = SimpleDoubleProperty(mapObject.alpha).apply { addListener { _, _, nv -> mapObject.alpha = nv.toDouble() } }
        val sizeProp = SimpleDoubleProperty(mapObject.currentSize).apply { addListener { _, _, nv -> mapObject.currentSize = nv.toDouble() } }

        val timeline = Timeline(
            KeyFrame(Duration.ZERO, KeyValue(alphaProp, mapObject.alpha), KeyValue(sizeProp, mapObject.currentSize)),
            KeyFrame(duration, KeyValue(alphaProp, 0.0), KeyValue(sizeProp, 0.0))
        )
        mapObject.activeAnimation = timeline
        timeline.setOnFinished {
            mapObject.activeAnimation = null; displayedObjects.remove(mapObject); activeAnimatedObjects.remove(mapObject)
        }
        timeline.play()
        startAnimationLoopIfNeeded()
    }

    fun updateVehicleAnimated(updatedVehicle: Vehicle) {
        val mapObject = displayedObjects.find { it.vehicle.id == updatedVehicle.id }
        if (mapObject == null) { addVehicleAnimated(updatedVehicle); return }

        mapObject.activeAnimation?.stop()
        activeAnimatedObjects.add(mapObject)

        val newCanvasX = scaleX(updatedVehicle.coordinates.x)
        val newCanvasY = scaleY(updatedVehicle.coordinates.y)
        val newTargetSize = calculateObjectSize(updatedVehicle.enginePower)

        val duration = Duration.millis(500.0)
        val xProp = SimpleDoubleProperty(mapObject.currentX).apply { addListener { _, _, nv -> mapObject.currentX = nv.toDouble() } }
        val yProp = SimpleDoubleProperty(mapObject.currentY).apply { addListener { _, _, nv -> mapObject.currentY = nv.toDouble() } }
        val sizeProp = SimpleDoubleProperty(mapObject.currentSize).apply { addListener { _, _, nv -> mapObject.currentSize = nv.toDouble() } }

        val timeline = Timeline(
            KeyFrame(Duration.ZERO, KeyValue(xProp, mapObject.currentX), KeyValue(yProp, mapObject.currentY), KeyValue(sizeProp, mapObject.currentSize)),
            KeyFrame(duration, KeyValue(xProp, newCanvasX), KeyValue(yProp, newCanvasY), KeyValue(sizeProp, newTargetSize))
        )
        mapObject.activeAnimation = timeline
        timeline.setOnFinished {
            mapObject.activeAnimation = null
            val index = displayedObjects.indexOfFirst { it.vehicle.id == updatedVehicle.id }
            if (index != -1) {
                displayedObjects[index] = mapObject.copy(
                    vehicle = updatedVehicle, currentX = newCanvasX, currentY = newCanvasY, currentSize = newTargetSize, color = getUserColor(updatedVehicle.userId) // Обновляем цвет тоже
                )
            }
            activeAnimatedObjects.remove(mapObject)
            redrawAll() // Финальная перерисовка после обновления данных объекта
        }
        timeline.play()
        startAnimationLoopIfNeeded()
    }

    fun redrawAll() {
        if (mapCanvas.width <= 0 || mapCanvas.height <= 0) return
        gc.clearRect(0.0, 0.0, mapCanvas.width, mapCanvas.height)

        displayedObjects.forEach { obj ->
            gc.globalAlpha = obj.alpha.coerceIn(0.0, 1.0)
            gc.fill = obj.color
            val radius = obj.currentSize / 2.0
            if (radius > 0) {
                val drawX = obj.currentX
                val drawY = obj.currentY
                gc.fillOval(drawX - radius, drawY - radius, obj.currentSize, obj.currentSize)
                if (obj.currentSize > 15 && obj.alpha > 0.5) {
                    gc.fill = Color.BLACK
                    gc.font = Font.font("System", FontWeight.NORMAL, 10.0)
                    gc.textAlign = TextAlignment.CENTER
                    gc.textBaseline = VPos.CENTER
                    gc.fillText(obj.vehicle.id.toString(), drawX, drawY)
                }
            }
            gc.globalAlpha = 1.0
        }
    }

    private fun findClickedObject(clickX: Double, clickY: Double): Vehicle? {
        for (obj in displayedObjects.asReversed()) {
            val dx = clickX - obj.currentX
            val dy = clickY - obj.currentY
            val clickRadiusForThisObject = obj.currentSize / 2.0 + 2.0
            if (dx * dx + dy * dy < clickRadiusForThisObject * clickRadiusForThisObject) {
                return obj.vehicle
            }
        }
        return null
    }

    fun getDisplayedObjectsCount(): Int = displayedObjects.size
}