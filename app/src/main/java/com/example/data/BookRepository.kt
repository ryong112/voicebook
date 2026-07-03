package com.example.data

import kotlinx.coroutines.flow.Flow

class BookRepository(private val bookDao: BookDao) {
    val allBooks: Flow<List<Book>> = bookDao.getAllBooks()

    suspend fun getBookByTitle(title: String): Book? {
        return bookDao.getBookByTitle(title)
    }

    suspend fun saveOrAppendBook(title: String, newText: String) {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty()) return
        val existingBook = bookDao.getBookByTitle(trimmedTitle)
        if (existingBook != null) {
            val updatedText = if (existingBook.text.isEmpty()) {
                newText
            } else {
                existingBook.text + "\n\n" + newText
            }
            bookDao.updateBook(existingBook.copy(text = updatedText, lastUpdated = System.currentTimeMillis()))
        } else {
            val newBook = Book(title = trimmedTitle, text = newText)
            bookDao.insertBook(newBook)
        }
    }

    suspend fun deleteBook(id: Long) {
        bookDao.deleteBookById(id)
    }

    suspend fun updateBook(book: Book) {
        bookDao.updateBook(book)
    }
}
