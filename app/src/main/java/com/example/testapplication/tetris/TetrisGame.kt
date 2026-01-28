package com.example.testapplication.tetris

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlin.random.Random

// --- Data Structures ---

data class Point(val x: Int, val y: Int)

enum class TetrominoType(val shape: List<List<Int>>, val color: Color) {
    I(listOf(listOf(1, 1, 1, 1)), Color(0xFF00BCD4)),
    J(listOf(listOf(1, 0, 0), listOf(1, 1, 1)), Color(0xFF3F51B5)),
    L(listOf(listOf(0, 0, 1), listOf(1, 1, 1)), Color(0xFFFF9800)),
    O(listOf(listOf(1, 1), listOf(1, 1)), Color(0xFFFFEB3B)),
    S(listOf(listOf(0, 1, 1), listOf(1, 1, 0)), Color(0xFF4CAF50)),
    T(listOf(listOf(0, 1, 0), listOf(1, 1, 1)), Color(0xFF9C27B0)),
    Z(listOf(listOf(1, 1, 0), listOf(0, 1, 1)), Color(0xFFF44336));

    companion object {
        fun random() = entries[Random.nextInt(entries.size)]
    }
}

data class Piece(
    val type: TetrominoType,
    val position: Point, // Top-left coordinate
    val rotation: Int = 0 // 0, 1, 2, 3 (x90 degrees)
) {
    val shapeMatrix: List<List<Int>>
        get() {
            var matrix = type.shape
            repeat(rotation % 4) {
                matrix = rotateMatrix(matrix)
            }
            return matrix
        }

    private fun rotateMatrix(matrix: List<List<Int>>): List<List<Int>> {
        val rows = matrix.size
        val cols = matrix[0].size
        val newMatrix = Array(cols) { IntArray(rows).toMutableList() }.toMutableList()
        for (i in 0 until rows) {
            for (j in 0 until cols) {
                newMatrix[j][rows - 1 - i] = matrix[i][j]
            }
        }
        return newMatrix
    }
}

data class TetrisState(
    val board: List<List<Color?>> = List(20) { List(10) { null } }, // 20 rows, 10 cols
    val currentPiece: Piece? = null,
    val score: Int = 0,
    val isGameOver: Boolean = false,
    val isPaused: Boolean = false
)

// --- ViewModel ---

class TetrisViewModel : ViewModel() {
    private val _state = MutableStateFlow(TetrisState())
    val state = _state.asStateFlow()

    private val BOARD_WIDTH = 10
    private val BOARD_HEIGHT = 20

    init {
        startGame()
    }

    fun startGame() {
        _state.update {
            TetrisState(
                currentPiece = spawnPiece(),
                isGameOver = false,
                isPaused = false,
                score = 0
            )
        }
    }

    suspend fun gameLoop() {
        while (true) {
            val currentState = _state.value
            if (!currentState.isPaused && !currentState.isGameOver) {
                if (!movePiece(0, 1)) {
                    lockPiece()
                    clearLines()
                    spawnNextPiece()
                }
            }
            val speed = (800 - (state.value.score / 100) * 50).coerceAtLeast(100)
            delay(speed.toLong())
        }
    }

    fun moveLeft() {
        if (!_state.value.isPaused && !_state.value.isGameOver) movePiece(-1, 0)
    }

    fun moveRight() {
        if (!_state.value.isPaused && !_state.value.isGameOver) movePiece(1, 0)
    }

    fun rotate() {
        if (_state.value.isPaused || _state.value.isGameOver) return
        _state.update { s ->
            s.currentPiece?.let { piece ->
                val nextRotation = piece.copy(rotation = piece.rotation + 1)
                if (isValidPosition(nextRotation, s.board)) {
                    s.copy(currentPiece = nextRotation)
                } else {
                    s
                }
            } ?: s
        }
    }

    fun drop() {
        if (_state.value.isPaused || _state.value.isGameOver) return
        // Drop until collision
        while (movePiece(0, 1)) { }
    }

    fun togglePause() {
        _state.update { it.copy(isPaused = !it.isPaused) }
    }

    private fun spawnPiece(): Piece {
        val type = TetrominoType.random()
        return Piece(type, Point((BOARD_WIDTH - type.shape[0].size) / 2, 0))
    }

    private fun spawnNextPiece() {
        val nextPiece = spawnPiece()
        if (!isValidPosition(nextPiece, _state.value.board)) {
            _state.update { it.copy(isGameOver = true) }
        } else {
            _state.update { it.copy(currentPiece = nextPiece) }
        }
    }

    fun moveDown() {
        if (!_state.value.isPaused && !_state.value.isGameOver) movePiece(0, 1)
    }

    private fun movePiece(dx: Int, dy: Int): Boolean {
        var success = false
        _state.update { s ->
            s.currentPiece?.let { piece ->
                val nextPosition = Point(piece.position.x + dx, piece.position.y + dy)
                val nextPiece = piece.copy(position = nextPosition)
                if (isValidPosition(nextPiece, s.board)) {
                    success = true
                    s.copy(currentPiece = nextPiece)
                } else {
                    s
                }
            } ?: s
        }
        return success
    }

    private fun isValidPosition(piece: Piece, board: List<List<Color?>>): Boolean {
        val matrix = piece.shapeMatrix
        for (i in matrix.indices) {
            for (j in matrix[i].indices) {
                if (matrix[i][j] == 1) {
                    val x = piece.position.x + j
                    val y = piece.position.y + i
                    if (x < 0 || x >= BOARD_WIDTH || y >= BOARD_HEIGHT) return false
                    if (y >= 0 && board[y][x] != null) return false
                }
            }
        }
        return true
    }

    private fun lockPiece() {
        _state.update { s ->
            s.currentPiece?.let { piece ->
                val newBoard = s.board.map { it.toMutableList() }.toMutableList()
                val matrix = piece.shapeMatrix
                for (i in matrix.indices) {
                    for (j in matrix[i].indices) {
                        if (matrix[i][j] == 1) {
                            val x = piece.position.x + j
                            val y = piece.position.y + i
                            if (y >= 0) {
                                newBoard[y][x] = piece.type.color
                            }
                        }
                    }
                }
                s.copy(board = newBoard, currentPiece = null)
            } ?: s
        }
    }

    private fun clearLines() {
        _state.update { s ->
            val newBoard = s.board.filter { row -> row.any { it == null } }.toMutableList()
            val linesCleared = BOARD_HEIGHT - newBoard.size
            repeat(linesCleared) {
                newBoard.add(0, List(BOARD_WIDTH) { null })
            }
            s.copy(
                board = newBoard,
                score = s.score + when (linesCleared) {
                    1 -> 100
                    2 -> 300
                    3 -> 500
                    4 -> 800
                    else -> 0
                }
            )
        }
    }
}

// --- UI ---

@Composable
fun TetrisScreen(viewModel: TetrisViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.gameLoop()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF202020))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Score & Status
        Text(
            text = "Score: ${state.score}",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White
        )

        if (state.isGameOver) {
            Text(
                text = "GAME OVER",
                style = MaterialTheme.typography.headlineLarge,
                color = Color.Red,
                modifier = Modifier.padding(8.dp)
            )
            Button(onClick = { viewModel.startGame() }) {
                Text("Restart")
            }
        } else if (state.isPaused) {
            Text(
                text = "PAUSED",
                style = MaterialTheme.typography.headlineLarge,
                color = Color.Yellow,
                modifier = Modifier.padding(8.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Game Board
        Box(
            modifier = Modifier
                .weight(1f)
                .aspectRatio(10f / 20f)
                .background(Color.Black)
                .padding(2.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cellWidth = size.width / 10
                val cellHeight = size.height / 20

                // Draw Board
                state.board.forEachIndexed { y, row ->
                    row.forEachIndexed { x, color ->
                        if (color != null) {
                            drawRect(
                                color = color,
                                topLeft = Offset(x * cellWidth, y * cellHeight),
                                size = Size(cellWidth - 1, cellHeight - 1)
                            )
                        }
                    }
                }

                // Draw Current Piece
                state.currentPiece?.let { piece ->
                    val matrix = piece.shapeMatrix
                    for (i in matrix.indices) {
                        for (j in matrix[i].indices) {
                            if (matrix[i][j] == 1) {
                                val x = piece.position.x + j
                                val y = piece.position.y + i
                                if (y >= 0) { // Only draw if visible
                                    drawRect(
                                        color = piece.type.color,
                                        topLeft = Offset(x * cellWidth, y * cellHeight),
                                        size = Size(cellWidth - 1, cellHeight - 1)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
             ControlBtn("←") { viewModel.moveLeft() }
             ControlBtn("↻") { viewModel.rotate() }
             ControlBtn("→") { viewModel.moveRight() }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ControlBtn("↓") { viewModel.moveDown() } 
            ControlBtn("Drop") { viewModel.drop() }
            ControlBtn(if (state.isPaused) "Resume" else "Pause") { viewModel.togglePause() }
        }
    }
}

@Composable
fun ControlBtn(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(60.dp).width(80.dp),
        shape = MaterialTheme.shapes.medium
    ) {
        Text(text, fontSize = 18.sp)
    }
}

// Helper removed

