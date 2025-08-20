fn main() -> i32 {
    let x = 5;
    let y = z + 10;  // z is undeclared
    return y;
}

fn test() -> bool {
    return 42;  // type mismatch: returning int instead of bool
}