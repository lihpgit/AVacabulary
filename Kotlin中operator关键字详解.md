# Kotlin 中 operator 关键字详解

## 概述

`operator` 是 Kotlin 中的一个关键字，用于重载操作符。它允许我们为自定义类型定义操作符的行为，使得代码更加简洁和直观。

## 基本概念

### 什么是操作符重载？

操作符重载是指为自定义类型定义操作符的行为，使得我们可以像使用基本类型一样使用自定义类型。

```kotlin
// 不使用操作符重载
val result = a.add(b)

// 使用操作符重载
val result = a + b
```

### operator 关键字的作用

`operator` 关键字告诉编译器这是一个操作符重载函数，编译器会根据函数名和参数类型来匹配相应的操作符。

## 可重载的操作符

### 1. 算术操作符

#### 一元操作符
```kotlin
class Number(val value: Int) {
    // 一元加号 (+a)
    operator fun unaryPlus(): Number = Number(+value)
    
    // 一元减号 (-a)
    operator fun unaryMinus(): Number = Number(-value)
    
    // 递增 (++a, a++)
    operator fun inc(): Number = Number(value + 1)
    
    // 递减 (--a, a--)
    operator fun dec(): Number = Number(value - 1)
    
    // 逻辑非 (!a)
    operator fun not(): Boolean = value == 0
}
```

#### 二元操作符
```kotlin
class Number(val value: Int) {
    // 加法 (a + b)
    operator fun plus(other: Number): Number = Number(value + other.value)
    
    // 减法 (a - b)
    operator fun minus(other: Number): Number = Number(value - other.value)
    
    // 乘法 (a * b)
    operator fun times(other: Number): Number = Number(value * other.value)
    
    // 除法 (a / b)
    operator fun div(other: Number): Number = Number(value / other.value)
    
    // 取模 (a % b)
    operator fun rem(other: Number): Number = Number(value % other.value)
}
```

### 2. 比较操作符

```kotlin
class Number(val value: Int) {
    // 等于 (a == b)
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Number) return false
        return value == other.value
    }
    
    // 小于 (a < b)
    operator fun compareTo(other: Number): Int = value.compareTo(other.value)
    
    // 注意：== 和 != 会自动使用 equals 方法
    // <, >, <=, >= 会自动使用 compareTo 方法
}
```

### 3. 索引操作符

```kotlin
class Matrix(val rows: Int, val cols: Int) {
    private val data = Array(rows) { Array(cols) { 0 } }
    
    // 获取元素 (matrix[i, j])
    operator fun get(row: Int, col: Int): Int = data[row][col]
    
    // 设置元素 (matrix[i, j] = value)
    operator fun set(row: Int, col: Int, value: Int) {
        data[row][col] = value
    }
    
    // 也可以重载单个参数的索引
    operator fun get(index: Int): Int = data[index / cols][index % cols]
    operator fun set(index: Int, value: Int) {
        data[index / cols][index % cols] = value
    }
}
```

### 4. 调用操作符

```kotlin
class Function {
    // 函数调用 (function())
    operator fun invoke(): String = "Function called"
    
    // 带参数的函数调用 (function(param))
    operator fun invoke(param: String): String = "Function called with: $param"
    
    // 多个参数的函数调用 (function(param1, param2))
    operator fun invoke(param1: String, param2: Int): String = 
        "Function called with: $param1, $param2"
}
```

### 5. 范围操作符

```kotlin
class Range(val start: Int, val end: Int) {
    // 范围操作符 (start..end)
    operator fun rangeTo(other: Int): IntRange = start..other
    
    // 包含操作符 (element in range)
    operator fun contains(element: Int): Boolean = element in start..end
}
```

### 6. 赋值操作符

```kotlin
class Number(var value: Int) {
    // 加法赋值 (a += b)
    operator fun plusAssign(other: Number) {
        value += other.value
    }
    
    // 减法赋值 (a -= b)
    operator fun minusAssign(other: Number) {
        value -= other.value
    }
    
    // 乘法赋值 (a *= b)
    operator fun timesAssign(other: Number) {
        value *= other.value
    }
    
    // 除法赋值 (a /= b)
    operator fun divAssign(other: Number) {
        value /= other.value
    }
    
    // 取模赋值 (a %= b)
    operator fun remAssign(other: Number) {
        value %= other.value
    }
}
```

## 实际应用示例

### 1. 复数类

```kotlin
data class Complex(val real: Double, val imaginary: Double) {
    // 加法
    operator fun plus(other: Complex): Complex =
        Complex(real + other.real, imaginary + other.imaginary)
    
    // 减法
    operator fun minus(other: Complex): Complex =
        Complex(real - other.real, imaginary - other.imaginary)
    
    // 乘法
    operator fun times(other: Complex): Complex =
        Complex(
            real * other.real - imaginary * other.imaginary,
            real * other.imaginary + imaginary * other.real
        )
    
    // 一元减号
    operator fun unaryMinus(): Complex = Complex(-real, -imaginary)
    
    override fun toString(): String = "$real + ${imaginary}i"
}

// 使用示例
fun complexExample() {
    val a = Complex(1.0, 2.0)
    val b = Complex(3.0, 4.0)
    
    println(a + b)  // 4.0 + 6.0i
    println(a - b)  // -2.0 + -2.0i
    println(a * b)  // -5.0 + 10.0i
    println(-a)     // -1.0 + -2.0i
}
```

### 2. 矩阵类

```kotlin
class Matrix(val rows: Int, val cols: Int) {
    private val data = Array(rows) { Array(cols) { 0.0 } }
    
    // 获取元素
    operator fun get(row: Int, col: Int): Double = data[row][col]
    
    // 设置元素
    operator fun set(row: Int, col: Int, value: Double) {
        data[row][col] = value
    }
    
    // 矩阵加法
    operator fun plus(other: Matrix): Matrix {
        require(rows == other.rows && cols == other.cols) { "Matrix dimensions must match" }
        val result = Matrix(rows, cols)
        for (i in 0 until rows) {
            for (j in 0 until cols) {
                result[i, j] = this[i, j] + other[i, j]
            }
        }
        return result
    }
    
    // 矩阵乘法
    operator fun times(other: Matrix): Matrix {
        require(cols == other.rows) { "Matrix dimensions must be compatible" }
        val result = Matrix(rows, other.cols)
        for (i in 0 until rows) {
            for (j in 0 until other.cols) {
                for (k in 0 until cols) {
                    result[i, j] += this[i, k] * other[k, j]
                }
            }
        }
        return result
    }
    
    override fun toString(): String {
        return data.joinToString("\n") { row ->
            row.joinToString(" ")
        }
    }
}

// 使用示例
fun matrixExample() {
    val a = Matrix(2, 2)
    a[0, 0] = 1.0; a[0, 1] = 2.0
    a[1, 0] = 3.0; a[1, 1] = 4.0
    
    val b = Matrix(2, 2)
    b[0, 0] = 5.0; b[0, 1] = 6.0
    b[1, 0] = 7.0; b[1, 1] = 8.0
    
    println("Matrix A:")
    println(a)
    println("Matrix B:")
    println(b)
    println("A + B:")
    println(a + b)
    println("A * B:")
    println(a * b)
}
```

### 3. 自定义集合类

```kotlin
class CustomList<T>(private val elements: MutableList<T> = mutableListOf()) {
    // 获取元素
    operator fun get(index: Int): T = elements[index]
    
    // 设置元素
    operator fun set(index: Int, value: T) {
        elements[index] = value
    }
    
    // 添加元素
    operator fun plus(element: T): CustomList<T> {
        val newList = CustomList<T>()
        newList.elements.addAll(elements)
        newList.elements.add(element)
        return newList
    }
    
    // 包含操作符
    operator fun contains(element: T): Boolean = element in elements
    
    // 范围操作符
    operator fun rangeTo(other: Int): List<T> = elements.subList(0, other)
    
    fun add(element: T) = elements.add(element)
    fun size(): Int = elements.size
    
    override fun toString(): String = elements.toString()
}

// 使用示例
fun customListExample() {
    val list = CustomList<String>()
    list.add("Hello")
    list.add("World")
    
    println(list[0])           // Hello
    list[1] = "Kotlin"         // 修改元素
    println(list)              // [Hello, Kotlin]
    
    val newList = list + "!"   // 添加元素
    println(newList)           // [Hello, Kotlin, !]
    
    println("Hello" in list)   // true
    println(list[0..1])        // [Hello, Kotlin]
}
```

### 4. 函数式编程工具

```kotlin
class FunctionBuilder<T> {
    private val operations = mutableListOf<(T) -> T>()
    
    // 函数组合操作符
    operator fun plus(other: FunctionBuilder<T>): FunctionBuilder<T> {
        val result = FunctionBuilder<T>()
        result.operations.addAll(this.operations)
        result.operations.addAll(other.operations)
        return result
    }
    
    // 函数调用操作符
    operator fun invoke(input: T): T {
        var result = input
        for (operation in operations) {
            result = operation(result)
        }
        return result
    }
    
    fun addOperation(operation: (T) -> T) {
        operations.add(operation)
    }
}

// 使用示例
fun functionBuilderExample() {
    val double = FunctionBuilder<Int>().apply { 
        addOperation { it * 2 } 
    }
    val addOne = FunctionBuilder<Int>().apply { 
        addOperation { it + 1 } 
    }
    
    val combined = double + addOne  // 组合函数
    println(combined(5))            // 11 (5 * 2 + 1)
}
```

## 注意事项和最佳实践

### 1. 操作符重载的注意事项

```kotlin
class Number(val value: Int) {
    // 好的做法：保持操作符的语义
    operator fun plus(other: Number): Number = Number(value + other.value)
    
    // 不好的做法：改变操作符的语义
    // operator fun plus(other: Number): Number = Number(value * other.value) // 错误！
}
```

### 2. 类型一致性

```kotlin
class Number(val value: Int) {
    // 保持类型一致性
    operator fun plus(other: Number): Number = Number(value + other.value)
    operator fun plus(other: Int): Number = Number(value + other)
    
    // 注意：不能重载不同返回类型的相同操作符
    // operator fun plus(other: Number): String = "Result: ${value + other.value}" // 错误！
}
```

### 3. 性能考虑

```kotlin
class Matrix(val rows: Int, val cols: Int) {
    private val data = Array(rows) { Array(cols) { 0.0 } }
    
    // 好的做法：避免创建临时对象
    operator fun plusAssign(other: Matrix) {
        for (i in 0 until rows) {
            for (j in 0 until cols) {
                data[i][j] += other[i, j]
            }
        }
    }
    
    // 不好的做法：创建临时对象
    operator fun plus(other: Matrix): Matrix {
        val result = Matrix(rows, cols)
        // ... 复制数据
        return result
    }
}
```

### 4. 可读性优先

```kotlin
// 好的做法：操作符使代码更简洁
val result = matrix1 + matrix2

// 不好的做法：过度使用操作符可能降低可读性
val result = matrix1 + matrix2 * matrix3 - matrix4 / matrix5
```

## 总结

`operator` 关键字是 Kotlin 中一个强大的特性，它允许我们：

1. **简化代码**：使自定义类型的操作更加直观
2. **提高可读性**：使用熟悉的操作符语法
3. **增强表达能力**：为特定领域创建专门的语法
4. **保持一致性**：与内置类型的使用方式保持一致

### 使用建议

1. **保持语义**：操作符重载应该保持操作符的原有语义
2. **类型安全**：确保操作符重载的类型安全
3. **性能考虑**：避免不必要的对象创建
4. **适度使用**：不要过度使用操作符重载，保持代码的可读性
5. **文档化**：为复杂的操作符重载提供清晰的文档

通过合理使用 `operator` 关键字，我们可以创建更加优雅和易用的 API，提高代码的表达能力和可维护性。 