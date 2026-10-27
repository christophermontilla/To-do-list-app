
const todoForm = document.getElementById("todo-form");
const todoInput = document.getElementById("todo-input");
const searchInput = document.getElementById("search-input");
const todoList = document.getElementById("todo-list");
const emptyState = document.getElementById("empty-state");

const totalCount = document.getElementById("total-count");
const activeCount = document.getElementById("active-count");
const completedCount = document.getElementById("completed-count");

const filterButtons = document.querySelectorAll(".filter-btn");
const clearCompletedButton =
    document.getElementById("clear-completed");

let todos = loadTodos();
let currentFilter = "all";
let searchText = "";

// LOAD SAVED TASKS
function loadTodos() {
    try {
        const savedTodos = localStorage.getItem("my-todo-tasks");
        const parsedTodos = savedTodos ? JSON.parse(savedTodos) : [];

        if (!Array.isArray(parsedTodos)) {
            return [];
        }

        return parsedTodos.filter(todo =>
            todo &&
            typeof todo.id === "string" &&
            typeof todo.text === "string" &&
            typeof todo.completed === "boolean"
        );
    } catch (error) {
        console.error("Could not load saved tasks:", error);
        return [];
    }
}

// SAVE TASKS
function saveTodos() {
    try {
        localStorage.setItem("my-todo-tasks", JSON.stringify(todos));
        return true;
    } catch (error) {
        console.error("Could not save tasks:", error);
        alert("Your changes could not be saved in this browser.");
        return false;
    }
}

// CREATE A UNIQUE TASK ID
function createId() {
    if (window.crypto && window.crypto.randomUUID) {
        return window.crypto.randomUUID();
    }

    return Date.now().toString(36) +
        Math.random().toString(36).slice(2);
}

// ADD A NEW TASK
todoForm.addEventListener("submit", function (event) {
    event.preventDefault();

    const text = todoInput.value.trim();

    if (!text) {
        todoInput.focus();
        return;
    }

    const newTodo = {
        id: createId(),
        text: text,
        completed: false,
        createdAt: new Date().toISOString()
    };

    todos.unshift(newTodo);
    saveTodos();

    todoInput.value = "";
    todoInput.focus();

    renderTodos();
});

// UPDATE STATISTICS
function updateStats() {
    const total = todos.length;
    const completed = todos.filter(todo => todo.completed).length;
    const active = total - completed;

    totalCount.textContent = total;
    activeCount.textContent = active;
    completedCount.textContent = completed;
}

// FILTER AND SEARCH TASKS
function getVisibleTodos() {
    return todos.filter(todo => {
        const matchesSearch = todo.text
            .toLowerCase()
            .includes(searchText.toLowerCase());

        const matchesFilter =
            currentFilter === "all" ||
            (currentFilter === "active" && !todo.completed) ||
            (currentFilter === "completed" && todo.completed);

        return matchesSearch && matchesFilter;
    });
}

// DISPLAY TASKS
function renderTodos() {
    todoList.replaceChildren();

    const visibleTodos = getVisibleTodos();

    visibleTodos.forEach(todo => {
        const li = document.createElement("li");
        li.className = "todo-item";

        if (todo.completed) {
            li.classList.add("completed");
        }

        // CHECKBOX
        const checkbox = document.createElement("input");
        checkbox.type = "checkbox";
        checkbox.className = "todo-checkbox";
        checkbox.checked = todo.completed;
        checkbox.setAttribute(
            "aria-label",
            `Mark ${todo.text} as ${todo.completed ? "active" : "completed"}`
        );

        checkbox.addEventListener("change", () => {
            toggleTodo(todo.id);
        });

        // TASK CONTENT
        const content = document.createElement("div");
        content.className = "task-content";

        const taskText = document.createElement("span");
        taskText.className = "task-text";
        taskText.textContent = todo.text;

        content.appendChild(taskText);

        if (todo.createdAt) {
            const date = new Date(todo.createdAt);

            if (!Number.isNaN(date.getTime())) {
                const dateLabel = document.createElement("small");
                dateLabel.className = "task-date";
                dateLabel.textContent =
                    "Added " + date.toLocaleDateString();

                content.appendChild(dateLabel);
            }
        }

        // ACTION BUTTONS
        const actions = document.createElement("div");
        actions.className = "task-actions";

        const editButton = document.createElement("button");
        editButton.className = "action-btn edit-btn";
        editButton.type = "button";
        editButton.textContent = "Edit";
        editButton.setAttribute("aria-label", `Edit ${todo.text}`);

        editButton.addEventListener("click", () => {
            editTodo(todo.id);
        });

        const deleteButton = document.createElement("button");
        deleteButton.className = "action-btn delete-btn";
        deleteButton.type = "button";
        deleteButton.textContent = "Delete";
        deleteButton.setAttribute("aria-label", `Delete ${todo.text}`);

        deleteButton.addEventListener("click", () => {
            deleteTodo(todo.id);
        });

        actions.append(editButton, deleteButton);
        li.append(checkbox, content, actions);
        todoList.appendChild(li);
    });

    // EMPTY STATE
    emptyState.hidden = visibleTodos.length > 0;
    todoList.hidden = visibleTodos.length === 0;

    const emptyTitle = emptyState.querySelector("h3");
    const emptyMessage = emptyState.querySelector("p");

    if (todos.length === 0) {
        emptyTitle.textContent = "No tasks yet";
        emptyMessage.textContent =
            "Add your first task to get started!";
    } else if (visibleTodos.length === 0 && searchText) {
        emptyTitle.textContent = "No matching tasks";
        emptyMessage.textContent =
            "Try another search or change your filter.";
    } else {
        emptyTitle.textContent = "Nothing to show";
        emptyMessage.textContent =
            "There are no tasks in this category.";
    }

    updateStats();
}

// COMPLETE OR UNCOMPLETE A TASK
function toggleTodo(id) {
    todos = todos.map(todo => {
        if (todo.id === id) {
            return {
                ...todo,
                completed: !todo.completed
            };
        }

        return todo;
    });

    saveTodos();
    renderTodos();
}

// EDIT A TASK
function editTodo(id) {
    const todo = todos.find(item => item.id === id);

    if (!todo) return;

    const newText = prompt("Edit your task:", todo.text);

    if (newText === null) return;

    const trimmedText = newText.trim();

    if (!trimmedText) {
        alert("Task cannot be empty.");
        return;
    }

    todos = todos.map(item => {
        if (item.id === id) {
            return {
                ...item,
                text: trimmedText
            };
        }

        return item;
    });

    saveTodos();
    renderTodos();
}

// DELETE A TASK
function deleteTodo(id) {
    const todo = todos.find(item => item.id === id);

    if (!todo) return;

    const confirmed = confirm(
        `Are you sure you want to delete "${todo.text}"?`
    );

    if (!confirmed) return;

    todos = todos.filter(item => item.id !== id);

    saveTodos();
    renderTodos();
}

// CHANGE FILTER
filterButtons.forEach(button => {
    button.addEventListener("click", () => {
        currentFilter = button.dataset.filter;

        filterButtons.forEach(btn => {
            const isActive = btn === button;

            btn.classList.toggle("active", isActive);
            btn.setAttribute("aria-pressed", String(isActive));
        });

        renderTodos();
    });
});

// SEARCH TASKS
searchInput.addEventListener("input", () => {
    searchText = searchInput.value.trim();
    renderTodos();
});

// DELETE ALL COMPLETED TASKS
clearCompletedButton.addEventListener("click", () => {
    const completed = todos.filter(todo => todo.completed);

    if (completed.length === 0) {
        alert("There are no completed tasks to clear.");
        return;
    }

    const confirmed = confirm(
        `Delete all ${completed.length} completed task(s)?`
    );

    if (!confirmed) return;

    todos = todos.filter(todo => !todo.completed);

    saveTodos();
    renderTodos();
});

// INITIAL DISPLAY
renderTodos();
