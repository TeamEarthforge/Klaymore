package org.bincnpcscript

import com.sun.jna.*
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.win32.StdCallLibrary
import kotlin.random.Random

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser.WNDENUMPROC

interface Win32API : StdCallLibrary {
    fun GetWindowRect(hWnd: HWND, lpRect: RECT): Boolean
    fun SetWindowPos(hWnd: HWND, hWndInsertAfter: HWND, X: Int, Y: Int, cx: Int, cy: Int, uFlags: Int): Boolean
    fun ShowWindow(hWnd: HWND, nCmdShow: Int): Boolean
    fun GetWindowThreadProcessId(hWnd: HWND, lpdwProcessId: IntArray?): Int
    fun EnumWindows(lpEnumFunc: WNDENUMPROC, lParam: Pointer): Boolean
    fun GetWindowTextA(hWnd: HWND, lpString: ByteArray, nMaxCount: Int): Int
    fun GetClassNameA(hWnd: HWND, lpClassName: ByteArray, nMaxCount: Int): Int

    class RECT : Structure() {
        @JvmField var left = 0
        @JvmField var top = 0
        @JvmField var right = 0
        @JvmField var bottom = 0

        override fun getFieldOrder(): List<String> = listOf("left", "top", "right", "bottom")
    }

    companion object {
        val INSTANCE: Win32API = Native.loadLibrary<Win32API>("user32", Win32API::class.java)
    }
}

// 扩展函数，方便使用
fun Win32API.getWindowRect(hWnd: HWND): Win32API.RECT? {
    return Win32API.RECT().apply {
        if (!GetWindowRect(hWnd, this)) return null
    }
}
object New{
    fun bindTarget(hWnd : WinDef.HWND){
        val rect = Win32API.RECT();
        if (Win32API.INSTANCE.GetWindowRect(hWnd, rect)) {
            val newX = rect.left + Random.nextInt(20) - 10;
            val newY = rect.top + Random.nextInt(20) - 10;
            val width = rect.right - rect.left;
            val height = rect.bottom - rect.top;
            Win32API.INSTANCE.SetWindowPos(hWnd, HWND(Pointer.NULL), newX, newY, width, height, 4);
        }
    }
}
