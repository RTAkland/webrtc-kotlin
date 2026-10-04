CXX = x86_64-w64-mingw32-g++-posix
AR = x86_64-w64-mingw32-ar

TARGET = libthrow_bad_array_new_length.a
OBJ = throw_bad_array_new_length.o
SRC = throw_bad_array_new_length.cpp

all: $(TARGET)

$(OBJ): $(SRC)
	$(CXX) -c $(SRC) -o $(OBJ)

$(TARGET): $(OBJ)
	$(AR) rcs $(TARGET) $(OBJ)

clean:
	rm -f $(OBJ) $(TARGET)

.PHONY: all clean