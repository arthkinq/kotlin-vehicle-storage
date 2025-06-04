package gui

import javafx.animation.*
import javafx.beans.property.SimpleDoubleProperty
import javafx.scene.canvas.Canvas
import javafx.scene.canvas.GraphicsContext
import javafx.scene.input.MouseEvent
import javafx.scene.paint.Color
import javafx.scene.text.Font
import javafx.util.Duration
import model.Vehicle

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
    private val objectBaseSize = 20.0
    private val objectClickRadius = objectBaseSize / 2 + 5

    private var animationLoop: AnimationTimer? = null
    private val activeAnimatedObjects = mutableSetOf<MapObject>() // Объекты, чья анимация сейчас идет

    init {
        mapCanvas.addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
            findClickedObject(event.x, event.y)?.let { onObjectClick(it) }
        }
    }

    private fun startAnimationLoopIfNeeded() {
        if (animationLoop == null && activeAnimatedObjects.isNotEmpty()) { // Запускаем только если есть что анимировать
            println("MapVisualizationManager: Starting AnimationTimer because activeAnimatedObjects is not empty.")
            animationLoop = object : AnimationTimer() {
                override fun handle(now: Long) {
                    redrawAll()
                    // Останавливаемся, только если множество активных объектов пусто
                    // И все запущенные Timelines завершились (на всякий случай)
                    if (activeAnimatedObjects.isEmpty() && displayedObjects.none { it.activeAnimation?.status == Animation.Status.RUNNING }) {
                        this.stop()
                        animationLoop = null
                        println("MapVisualizationManager: AnimationTimer stopped.")
                        redrawAll() // Финальная перерисовка
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

    // Отображает все объекты статически, без анимации. Используется для полного обновления.
    fun replaceAllVehicles(vehicles: List<Vehicle>) {
        println("MapVisualizationManager: replaceAllVehicles called with ${vehicles.size} vehicles.")
        // Останавливаем ВСЕ активные анимации и очищаем связанные списки
        activeAnimatedObjects.forEach { it.activeAnimation?.stop() }
        activeAnimatedObjects.clear()
        displayedObjects.forEach { it.activeAnimation?.stop() } // На случай если объект был, но не в activeAnimatedObjects
        displayedObjects.clear()

        vehicles.forEach { vehicle ->
            val color = getUserColor(vehicle.userId)
            val canvasX = vehicle.coordinates.x.toDouble()
            val canvasY = vehicle.coordinates.y.toDouble()
            displayedObjects.add(
                MapObject(
                    vehicle = vehicle,
                    currentX = canvasX, currentY = canvasY, currentSize = objectBaseSize,
                    color = color, alpha = 1.0
                )
            )
        }
        redrawAll()
    }

    // Анимированно добавляет ОДИН объект
    fun addVehicleAnimated(vehicle: Vehicle) {
        if (displayedObjects.any { it.vehicle.id == vehicle.id }) {
            updateVehicleAnimated(vehicle); return
        }
        println("MapVisualizationManager: addVehicleAnimated for ID ${vehicle.id}")
        val color = getUserColor(vehicle.userId)
        val canvasX = vehicle.coordinates.x.toDouble() // TODO: Масштабирование
        val canvasY = vehicle.coordinates.y.toDouble() // TODO: Масштабирование

        val mapObject = MapObject(
            vehicle = vehicle,
            currentX = canvasX, currentY = canvasY, currentSize = 0.0, // Начинаем с нулевого размера
            color = color, alpha = 0.0 // Начинаем с прозрачного
        )
        displayedObjects.add(mapObject)
        activeAnimatedObjects.add(mapObject)

        val duration = Duration.millis(500.0)
        val alphaProp = SimpleDoubleProperty(0.0).apply { addListener { _, _, nv -> mapObject.alpha = nv.toDouble() } }
        val sizeProp =
            SimpleDoubleProperty(0.0).apply { addListener { _, _, nv -> mapObject.currentSize = nv.toDouble() } }

        val timeline = Timeline(
            KeyFrame(Duration.ZERO, KeyValue(alphaProp, 0.0), KeyValue(sizeProp, 0.0)),
            KeyFrame(duration, KeyValue(alphaProp, 1.0), KeyValue(sizeProp, objectBaseSize))
        )
        mapObject.activeAnimation = timeline

        timeline.setOnFinished {
            mapObject.activeAnimation = null
            mapObject.alpha = 1.0
            mapObject.currentSize = objectBaseSize
            activeAnimatedObjects.remove(mapObject) // <--- Удаляем из активных
            // redrawAll() НЕ ЗДЕСЬ, AnimationTimer это сделает
        }
        timeline.play()
        startAnimationLoopIfNeeded()
    }

    fun removeVehicleAnimated(vehicleId: Int) {
        val mapObject = displayedObjects.find { it.vehicle.id == vehicleId } ?: return
        println("MapVisualizationManager: removeVehicleAnimated for ID $vehicleId")
        mapObject.activeAnimation?.stop()
        activeAnimatedObjects.add(mapObject)

        val duration = Duration.millis(500.0)
        val alphaProp =
            SimpleDoubleProperty(mapObject.alpha).apply { addListener { _, _, nv -> mapObject.alpha = nv.toDouble() } }
        val sizeProp = SimpleDoubleProperty(mapObject.currentSize).apply {
            addListener { _, _, nv ->
                mapObject.currentSize = nv.toDouble()
            }
        }

        val timeline = Timeline(
            KeyFrame(Duration.ZERO, KeyValue(alphaProp, mapObject.alpha), KeyValue(sizeProp, mapObject.currentSize)),
            KeyFrame(duration, KeyValue(alphaProp, 0.0), KeyValue(sizeProp, 0.0))
        )
        mapObject.activeAnimation = timeline
        timeline.setOnFinished {
            mapObject.activeAnimation = null
            displayedObjects.remove(mapObject)
            activeAnimatedObjects.remove(mapObject) // <--- Удаляем из активных
            // redrawAll() НЕ ЗДЕСЬ
        }
        timeline.play()
        startAnimationLoopIfNeeded()
    }

    private fun updateVehicleAnimated(updatedVehicle: Vehicle) {
        val mapObject = displayedObjects.find { it.vehicle.id == updatedVehicle.id }
        if (mapObject == null) {
            addVehicleAnimated(updatedVehicle); return
        }
        println("MapVisualizationManager: updateVehicleAnimated for ID ${updatedVehicle.id}")
        mapObject.activeAnimation?.stop()
        activeAnimatedObjects.add(mapObject)

        val newCanvasX = updatedVehicle.coordinates.x.toDouble() // TODO: Масштабирование
        val newCanvasY = updatedVehicle.coordinates.y.toDouble() // TODO: Масштабирование
        val newTargetSize = objectBaseSize // TODO: Если размер зависит от updatedVehicle

        val duration = Duration.millis(500.0)
        val xProp = SimpleDoubleProperty(mapObject.currentX).apply {
            addListener { _, _, nv ->
                mapObject.currentX = nv.toDouble()
            }
        }
        val yProp = SimpleDoubleProperty(mapObject.currentY).apply {
            addListener { _, _, nv ->
                mapObject.currentY = nv.toDouble()
            }
        }
        val sizeProp = SimpleDoubleProperty(mapObject.currentSize).apply {
            addListener { _, _, nv ->
                mapObject.currentSize = nv.toDouble()
            }
        }


        val keyValuesEnd = mutableListOf<KeyValue>(
            KeyValue(xProp, newCanvasX),
            KeyValue(yProp, newCanvasY)
        )
        if (mapObject.currentSize != newTargetSize) { // Анимируем размер только если он изменился
            keyValuesEnd.add(KeyValue(sizeProp, newTargetSize))
        }


        val timeline = Timeline(
            KeyFrame(
                Duration.ZERO,
                KeyValue(xProp, mapObject.currentX),
                KeyValue(yProp, mapObject.currentY),
                KeyValue(sizeProp, mapObject.currentSize) // Начальный размер
            ),
            KeyFrame(duration, *keyValuesEnd.toTypedArray()) // Конечные значения
        )

        mapObject.activeAnimation = timeline
        timeline.setOnFinished {
            mapObject.activeAnimation = null
            val index = displayedObjects.indexOfFirst { it.vehicle.id == updatedVehicle.id }
            if (index != -1) {
                displayedObjects[index] = mapObject.copy( // Обновляем объект в списке
                    vehicle = updatedVehicle,
                    currentX = newCanvasX,
                    currentY = newCanvasY,
                    currentSize = newTargetSize
                )
            }
            activeAnimatedObjects.remove(mapObject)
            redrawAll() // Финальная точная отрисовка
        }
        timeline.play()
        startAnimationLoopIfNeeded()
    }

    fun redrawAll() {
        if (mapCanvas.width <= 0 || mapCanvas.height <= 0) {
            // println("MapVisualizationManager: redrawAll - Canvas not ready or zero size.")
            return
        }
        gc.clearRect(0.0, 0.0, mapCanvas.width, mapCanvas.height)

        // Тестовый квадрат, чтобы видеть, что Canvas рисует
        // gc.fill = Color.LIMEGREEN
        // gc.fillRect(10.0, 10.0, 50.0, 50.0)

        displayedObjects.forEach { obj ->
            gc.globalAlpha = obj.alpha.coerceIn(0.0, 1.0)
            gc.fill = obj.color
            val radius = obj.currentSize / 2.0
            if (radius > 0) { // Рисуем только если есть размер
                gc.fillOval(obj.currentX - radius, obj.currentY - radius, obj.currentSize, obj.currentSize)
                if (obj.currentSize > 15 && obj.alpha > 0.5) {
                    gc.fill = Color.BLACK
                    gc.font = Font.font(10.0)
                    gc.fillText(
                        obj.vehicle.id.toString(),
                        obj.currentX - radius + 2,
                        obj.currentY + 4
                    ) // Скорректировал Y для текста
                }
            }
            gc.globalAlpha = 1.0
        }
    }

    private fun findClickedObject(clickX: Double, clickY: Double): Vehicle? {
        // Ищем с конца, чтобы кликнуть по верхнему объекту, если они перекрываются
        for (obj in displayedObjects.asReversed()) {
            val dx = clickX - obj.currentX
            val dy = clickY - obj.currentY
            val distanceSquared = dx * dx + dy * dy
            if (distanceSquared < objectClickRadius * objectClickRadius) {
                return obj.vehicle
            }
        }
        return null
    }

    fun getDisplayedObjectsCount(): Int = displayedObjects.size
}


