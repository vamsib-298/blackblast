package com.blackblast.core

/** Clockwise 90° rotation via boolean grid — independent of Shapes.orientations. */
internal fun referenceRotate(shape: Shape): Set<Cell> {
    val height = shape.height
    val grid = Array(height) { Array(shape.width) { false } }
    shape.cells.forEach { grid[it.row][it.column] = true }
    val out = HashSet<Cell>()
    repeat(height) { r ->
        repeat(shape.width) { c ->
            if (grid[r][c]) out.add(Cell(c, height - 1 - r))
        }
    }
    return out
}

/** Compute piece cells by applying referenceRotate [rotation] times to Shapes.all[shapeId]. */
internal fun refCells(shapeId: Int, rotation: Int): List<Cell> {
    var current = Shapes.all[shapeId]
    repeat(rotation) {
        val rotated = referenceRotate(current).sortedWith(compareBy(Cell::row, Cell::column))
        current = Shape(current.name, rotated)
    }
    return current.cells
}

/** Placement validity using only array bounds and board occupancy — no GameEngine.canPlace. */
internal fun refCanPlace(board: List<Int>, cells: List<Cell>, row: Int, col: Int): Boolean =
    cells.all { cell ->
        val r = row + cell.row; val c = col + cell.column
        r in 0 until BOARD_SIZE && c in 0 until BOARD_SIZE && board[r * BOARD_SIZE + c] == 0
    }

/** Scan all 8 rows and 8 columns; return flat index lists for every full line. */
internal fun refFullLines(board: List<Int>): List<List<Int>> = buildList {
    repeat(BOARD_SIZE) { line ->
        val row = List(BOARD_SIZE) { line * BOARD_SIZE + it }
        val col = List(BOARD_SIZE) { it * BOARD_SIZE + line }
        if (row.all { board[it] != 0 }) add(row)
        if (col.all { board[it] != 0 }) add(col)
    }
}
