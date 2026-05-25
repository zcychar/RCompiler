    .option nopic
    .text
    .globl _start
_start:
    call  main
    li  a7, 93
    ecall

    .globl printf
printf:
    addi  sp, sp, -32
    sd  ra, 24(sp)
    sd  s0, 16(sp)
    sd  s1, 8(sp)
    mv  s0, a0
    mv  s1, a1

    lbu  t0, 1(s0)
    li  t1, 100
    beq  t0, t1, .Lprintf_d
    li  t1, 115
    beq  t0, t1, .Lprintf_s
    j  .Lprintf_done

.Lprintf_d:
    mv  a0, s1
    call  __rv64_print_int
    j  .Lprintf_maybe_newline

.Lprintf_s:
    mv  a0, s1
    call  __rv64_print_cstr

.Lprintf_maybe_newline:
    lbu  t0, 2(s0)
    li  t1, 10
    bne  t0, t1, .Lprintf_done
    li  a0, 1
    la  a1, .Lrv64_newline
    li  a2, 1
    li  a7, 64
    ecall

.Lprintf_done:
    li  a0, 0
    ld  s1, 8(sp)
    ld  s0, 16(sp)
    ld  ra, 24(sp)
    addi  sp, sp, 32
    ret

__rv64_print_int:
    addi  sp, sp, -80
    sd  ra, 72(sp)
    sd  s0, 64(sp)
    mv  s0, a0

    bgez  s0, .Lprint_abs_ready
    li  a0, 1
    la  a1, .Lrv64_minus
    li  a2, 1
    li  a7, 64
    ecall
    neg  s0, s0

.Lprint_abs_ready:
    addi  t3, sp, 48
    li  t4, 0
    li  t5, 10

.Lprint_digit_loop:
    remu  t2, s0, t5
    divu  s0, s0, t5
    addi  t3, t3, -1
    addi  t2, t2, 48
    sb  t2, 0(t3)
    addi  t4, t4, 1
    bnez  s0, .Lprint_digit_loop

    li  a0, 1
    mv  a1, t3
    mv  a2, t4
    li  a7, 64
    ecall

    ld  s0, 64(sp)
    ld  ra, 72(sp)
    addi  sp, sp, 80
    ret

__rv64_print_cstr:
    mv  t0, a0
    mv  t1, a0
.Lstrlen_loop:
    lbu  t2, 0(t1)
    beqz  t2, .Lstrlen_done
    addi  t1, t1, 1
    j  .Lstrlen_loop
.Lstrlen_done:
    sub  a2, t1, t0
    beqz  a2, .Lprint_cstr_done
    li  a0, 1
    mv  a1, t0
    li  a7, 64
    ecall
.Lprint_cstr_done:
    ret

    .globl scanf
scanf:
    addi  sp, sp, -48
    sd  ra, 40(sp)
    sd  s0, 32(sp)
    sd  s1, 24(sp)
    sd  s2, 16(sp)
    mv  s0, a1
    li  s1, 0
    li  s2, 0

.Lscanf_skip:
    call  __rv64_read_char
    li  t0, 0
    blt  a0, t0, .Lscanf_done
    li  t0, 32
    beq  a0, t0, .Lscanf_skip
    li  t0, 10
    beq  a0, t0, .Lscanf_skip
    li  t0, 13
    beq  a0, t0, .Lscanf_skip
    li  t0, 9
    beq  a0, t0, .Lscanf_skip
    li  t0, 45
    bne  a0, t0, .Lscanf_digit_check
    li  s1, 1
    call  __rv64_read_char

.Lscanf_digit_check:
    li  t0, 48
    blt  a0, t0, .Lscanf_finish_number
    li  t0, 57
    blt  t0, a0, .Lscanf_finish_number
    addi  a0, a0, -48
    li  t1, 10
    mul  s2, s2, t1
    add  s2, s2, a0
    call  __rv64_read_char
    j  .Lscanf_digit_check

.Lscanf_finish_number:
    beqz  s1, .Lscanf_store
    neg  s2, s2
.Lscanf_store:
    sw  s2, 0(s0)
.Lscanf_done:
    li  a0, 1
    ld  s2, 16(sp)
    ld  s1, 24(sp)
    ld  s0, 32(sp)
    ld  ra, 40(sp)
    addi  sp, sp, 48
    ret

__rv64_read_char:
    li  a0, 0
    la  a1, __rv64_stdin_byte
    li  a2, 1
    li  a7, 63
    ecall
    blez  a0, .Lread_eof
    la  t0, __rv64_stdin_byte
    lbu  a0, 0(t0)
    ret
.Lread_eof:
    li  a0, -1
    ret

    .globl malloc
malloc:
    la  t0, __rv64_heap_brk
    ld  t1, 0(t0)
    bnez  t1, .Lmalloc_have_brk
    la  t1, __rv64_heap_area
.Lmalloc_have_brk:
    addi  a0, a0, 15
    andi  a0, a0, -16
    add  t2, t1, a0
    sd  t2, 0(t0)
    mv  a0, t1
    ret

    .globl memcpy
memcpy:
    mv  t3, a0
    beqz  a2, .Lmemcpy_done
.Lmemcpy_loop:
    lbu  t0, 0(a1)
    sb  t0, 0(a0)
    addi  a1, a1, 1
    addi  a0, a0, 1
    addi  a2, a2, -1
    bnez  a2, .Lmemcpy_loop
.Lmemcpy_done:
    mv  a0, t3
    ret

    .section .rodata
.Lrv64_minus:
    .byte 45
.Lrv64_newline:
    .byte 10

    .bss
    .align 3
__rv64_stdin_byte:
    .zero 1
    .align 3
__rv64_heap_brk:
    .zero 8
    .align 4
__rv64_heap_area:
    .zero 67108864
