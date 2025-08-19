# operator 关键字核心要点总结

## 1. 基本概念

### 什么是 operator？
- **定义**：Kotlin 中用于重载操作符的关键字
- **作用**：为自定义类型定义操作符的行为
- **目标**：使代码更加简洁和直观

### 核心思想
```kotlin
// 不使用操作符重载
val result = a.add(b)

// 使用操作符重载
val result = a + b
```

## 2. 可重载的操作符类型

### 2.1 算术操作符

#### 一元操作符
```kotlin
operator fun unaryPlus(): Type    // +a
operator fun unaryMinus(): Type   // -a
operator fun inc(): Type          // ++a, a++
operator fun dec(): Type          // --a, a--
operator fun not(): Boolean       // !a
```

#### 二元操作符
```kotlin
operator fun plus(other: Type): Type      // a + b
operator fun minus(other: Type): Type     // a - b
operator fun times(other: Type): Type     // a * b
operator fun div(other: Type): Type       // a / b
operator fun rem(other: Type): Type       // a % b
```

### 2.2 比较操作符
```kotlin
override fun equals(other: Any?): Boolean  // a == b, a != b
operator fun compareTo(other: Type): Int   // a < b, a > b, a <= b, a >= b
```

### 2.3 索引操作符
```kotlin
operator fun get(index: Int): Type         // a[index]
operator fun set(index: Int, value: Type)  // a[index] = value
```

### 2.4 调用操作符
```kotlin
operator fun invoke(): Type                // a()
operator fun invoke(param: Type): Type     // a(param)
```

### 2.5 范围操作符
```kotlin
operator fun rangeTo(other: Type): Range   // a..b
operator fun contains(element: Type): Boolean // element in range
```

### 2.6 赋值操作符
```kotlin
operator fun plusAssign(other: Type)       // a += b
operator fun minusAssign(other: Type)      // a -= b
operator fun timesAssign(other: Type)      // a *= b
operator fun divAssign(other: Type)        // a /= b
operator fun remAssign(other: Type)        // a %= b
```

## 3. 实际应用场景

### 3.1 数学运算
```kotlin
data class Complex(val real: Double, val imaginary: Double) {
    operator fun plus(other: Complex): Complex =
        Complex(real + other.real, imaginary + other.imaginary)
    
    operator fun times(other: Complex): Complex =
        Complex(
            real * other.real - imaginary * other.imaginary,
            real * other.imaginary + imaginary * other.real
        )
}

// 使用
val a = Complex(1.0, 2.0)
val b = Complex(3.0, 4.0)
val result = a + b  // 简洁直观
```

### 3.2 集合操作
```kotlin
class CustomList<T> {
    operator fun get(index: Int): T = elements[index]
    operator fun set(index: Int, value: T) { elements[index] = value }
    operator fun contains(element: T): Boolean = element in elements
}

// 使用
val list = CustomList<String>()
list[0] = "Hello"           // 像数组一样使用
println("Hello" in list)    // 使用 in 操作符
```

### 3.3 函数式编程
```kotlin
class FunctionBuilder<T> {
    operator fun plus(other: FunctionBuilder<T>): FunctionBuilder<T> {
        // 函数组合逻辑
    }
    
    operator fun invoke(input: T): T {
        // 函数执行逻辑
    }
}

// 使用
val combined = function1 + function2  // 函数组合
val result = combined(input)          // 函数调用
```

## 4. 最佳实践

### 4.1 保持语义一致性
```kotlin
// 好的做法：保持操作符的原有语义
operator fun plus(other: Number): Number = Number(value + other.value)

// 不好的做法：改变操作符的语义
// operator fun plus(other: Number): Number = Number(value * other.value) // 错误！
```

### 4.2 类型安全
```kotlin
class Number(val value: Int) {
    // 保持类型一致性
    operator fun plus(other: Number): Number = Number(value + other.value)
    operator fun plus(other: Int): Number = Number(value + other)
    
    // 避免不同返回类型的相同操作符
    // operator fun plus(other: Number): String = "Result: ${value + other.value}" // 错误！
}
```

### 4.3 性能考虑
```kotlin
class Matrix {
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

### 4.4 可读性优先
```kotlin
// 好的做法：操作符使代码更简洁
val result = matrix1 + matrix2

// 不好的做法：过度使用可能降低可读性
val result = matrix1 + matrix2 * matrix3 - matrix4 / matrix5
```

## 5. 注意事项

### 5.1 操作符优先级
```kotlin
// 操作符的优先级是固定的，不能改变
// 例如：* 的优先级总是高于 +
val result = a + b * c  // 等价于 a + (b * c)
```

### 5.2 操作符结合性
```kotlin
// 大多数操作符是左结合的
val result = a + b + c  // 等价于 (a + b) + c
```

### 5.3 操作符重载的限制
```kotlin
// 不能重载的操作符：
// =, ==, !=, ===, !==, &&, ||, ?:, ::, ., ?., ->
// 不能创建新的操作符
// 不能改变操作符的优先级和结合性
```

## 6. 常见错误

### 6.1 语义不一致
```kotlin
// 错误：改变了操作符的语义
operator fun plus(other: Number): Number = Number(value * other.value)
```

### 6.2 类型不一致
```kotlin
// 错误：相同操作符返回不同类型
operator fun plus(other: Number): Number = Number(value + other.value)
operator fun plus(other: Number): String = "Result: ${value + other.value}" // 错误！
```

### 6.3 性能问题
```kotlin
// 错误：每次都创建新对象
operator fun plus(other: Matrix): Matrix {
    val result = Matrix(rows, cols)  // 创建新对象
    // ... 复制数据
    return result
}
```

## 7. 调试技巧

### 7.1 使用 toString()
```kotlin
class CustomNumber(val value: Int) {
    override fun toString(): String = "CustomNumber($value)"
    
    operator fun plus(other: CustomNumber): CustomNumber = 
        CustomNumber(value + other.value)
}

// 调试时可以看到清晰的结果
val result = a + b
println(result)  // 输出：CustomNumber(15)
```

### 7.2 添加日志
```kotlin
operator fun plus(other: CustomNumber): CustomNumber {
    println("Adding $this and $other")
    return CustomNumber(value + other.value)
}
```

## 8. 总结

### operator 关键字的优势

1. **代码简洁**：使自定义类型的操作更加直观
2. **可读性强**：使用熟悉的操作符语法
3. **表达能力**：为特定领域创建专门的语法
4. **一致性**：与内置类型的使用方式保持一致

### 使用建议

1. **保持语义**：操作符重载应该保持操作符的原有语义
2. **类型安全**：确保操作符重载的类型安全
3. **性能考虑**：避免不必要的对象创建
4. **适度使用**：不要过度使用操作符重载
5. **文档化**：为复杂的操作符重载提供清晰的文档

### 适用场景

- **数学运算**：复数、矩阵、向量等
- **集合操作**：自定义集合类型
- **函数式编程**：函数组合、管道操作
- **DSL 设计**：领域特定语言
- **API 设计**：使 API 更加直观

通过合理使用 `operator` 关键字，我们可以创建更加优雅和易用的 API，提高代码的表达能力和可维护性。 