// Simple comprehensive test for semantic analysis

fn main() -> i32 {
    let x = 10;
    let y = 20;
    let sum = add(x, y);
    return sum;
}

fn add(a: i32, b: i32) -> i32 {
    return a + b;
}

fn multiply(x: i32, y: i32) -> i32 {
    return x * y;
}